package com.autologpro.app

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import kr.autologpro.sync.SupabaseClient
import kr.autologpro.sync.SupabaseTransport
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

internal data class PurposeTip(val purpose: Purpose, val reason: String)
internal object VehiclePurposeRules {
    fun suggest(trip: TripRecord, records: List<TripRecord>, serviceDay: String = "", shop: String = ""): PurposeTip? {
        if (trip.purpose != Purpose.UNCLASSIFIED || trip.endAt == null) return null
        val zone = ZoneId.of("Asia/Seoul")
        val date = Instant.ofEpochMilli(trip.startAt).atZone(zone)
        if (date.toLocalDate().toString() == serviceDay && shop.isNotBlank() && trip.endPlace.contains(shop, ignoreCase = true))
            return PurposeTip(Purpose.BUSINESS, "등록한 정비소 방문")
        val previous = records.filter {
            it.id != trip.id && it.startAt < trip.startAt &&
                Instant.ofEpochMilli(it.startAt).atZone(zone).toLocalDate() == date.toLocalDate() &&
                (it.id < 0 && it.id % 2L == 0L) == (trip.id < 0 && trip.id % 2L == 0L) &&
                it.vehiclePlate == trip.vehiclePlate && it.driver == trip.driver &&
                trip.driver.isNotBlank() && trip.driver != "미확인" && trip.driver != "미입력"
        }.maxByOrNull { it.startAt }
        val m = date.hour * 60 + date.minute
        if (previous == null && date.dayOfWeek.value <= 5 && m in 360 until 630)
            return PurposeTip(Purpose.COMMUTE, "평일 출근 시간대 첫 운행")
        if (previous?.purpose in setOf(Purpose.BUSINESS, Purpose.COMMUTE) &&
            previous?.endAt?.let { trip.startAt - it in 0..5_400_000L } == true)
            return PurposeTip(requireNotNull(previous).purpose, "같은 차량의 직전 운행 목적")
        return null
    }
}

internal class DriveAutomation(
    private val host: InCarDashboardActivity,
    private val repo: CoreTripGateway,
    private val parked: () -> Boolean,
    private val records: (Boolean) -> List<TripRecord>,
    private val onChange: () -> Unit
) {
    private val api = SupabaseClient(host)
    private val prefs = host.getSharedPreferences("alp_vehicle_cloud_v1", Context.MODE_PRIVATE)
    private val profileStore = com.autologpro.data.LogbookProfileStore(host)
    private var context: JSONObject? = null
    private var running=false
    private var generation=0
    private var failure=""
    private var lastSync=0L
    private var cursor: JSONObject? = null
    private var mustSyncAgain=false
    @Volatile private var memos: Map<Long,String> = emptyMap()
    val enabled: Boolean get()=api.signedIn()
    val status: String get()=when {
        !enabled -> "Supabase 로그인 필요"
        failure.isNotEmpty()->failure
        lastSync==0L -> "서버 연결 확인 중"
        else -> "Supabase 동기화 완료"
    }
    private fun vehicle()=context?.optJSONObject("vehicle")
    private fun company()=context?.optJSONObject("company")
    private fun vehicleId()=vehicle()?.optString("id").orEmpty()
    private fun notify(text:String)=android.widget.Toast.makeText(host,text,android.widget.Toast.LENGTH_SHORT).show()
    fun memo(id:Long)=memos[id].orEmpty()
    fun tip(trip: TripRecord, rows: List<TripRecord>) = VehiclePurposeRules.suggest(trip, rows,
        prefs.getString("service_date","").orEmpty(),prefs.getString("service_shop","").orEmpty())
    private fun field(label:String,value:String="",password:Boolean=false)=EditText(host).apply {
        setSingleLine()
        inputType=InputType.TYPE_CLASS_TEXT or (if(password)InputType.TYPE_TEXT_VARIATION_PASSWORD else 0)
        hint=label;setText(value)
    }
    private fun stack(vararg fields:EditText)=LinearLayout(host).apply{
        orientation=LinearLayout.VERTICAL;setPadding(34,6,34,0)
        fields.forEach{addView(it)}
    }
    private fun JSONObject.string(key:String)=if(isNull(key)) "" else optString(key)
    private fun arr(o:JSONArray?)=if(o==null) emptyList() else (0 until o.length()).mapNotNull(o::optJSONObject)
    private fun now()=Instant.now().toString()
    private fun key(t:TripRecord)=if(t.id%2L!=0L) "sim-${-t.id/2}-${t.startAt}" else "real-${-t.id/2}-${t.startAt}"
    private fun slug(p:Purpose)=p.name.lowercase(java.util.Locale.ROOT)
    private fun idKey()="car_uuid:"+vehicleId()
    private fun entry(t:TripRecord)=prefs.getString("${idKey()}:${key(t)}",null)?.let{runCatching{JSONObject(it)}.getOrNull()}
    private fun save(t:TripRecord,e:JSONObject)=prefs.edit().putString("${idKey()}:${key(t)}",e.toString()).commit()
    private fun uiEventKey(t:TripRecord)="pending:${vehicleId()}:${key(t)}"
    private fun pending(t:TripRecord)=prefs.getString(uiEventKey(t),null)?.let{runCatching{JSONObject(it)}.getOrNull()}
    private fun selectedDriverKey()="selected_driver:${vehicleId()}"
    private fun memoEventKey(t:TripRecord)="pending_memo:${vehicleId()}:${key(t)}"
    private fun pendingMemo(t:TripRecord)=prefs.getString(memoEventKey(t),null)?.let{runCatching{JSONObject(it)}.getOrNull()}
    private fun assignmentKey(t:TripRecord)="assign:${vehicleId()}:${key(t)}"
    private fun formatDate(t:TripRecord)=Instant.ofEpochMilli(t.startAt).atZone(ZoneId.of("Asia/Seoul"))
        .format(java.time.format.DateTimeFormatter.ofPattern("MM.dd HH:mm"))

    fun configure(){
        if(!parked()){notify("주차한 뒤 로그인하세요.");return}
        val pub=field("Supabase 공개 키 · anon 또는 publishable",api.publicKey())
        val email=field("차량 단말 계정 이메일")
        val pass=field("차량 단말 비밀번호",password=true)
        AlertDialog.Builder(host).setTitle("Supabase 차량 로그인").setMessage(SupabaseTransport.DEFAULT_ORIGIN)
            .setView(stack(pub,email,pass)).setNegativeButton("취소",null)
            .setNeutralButton("로그아웃"){_,_->
                generation++;running=false;context=null;cursor=null;memos=emptyMap();lastSync=0
                api.signOut{_,_,_->onChange();notify("차량 계정에서 로그아웃했어요.")}
            }.setPositiveButton("로그인"){_,_->
                if(!parked())return@setPositiveButton
                if(!SupabaseTransport.isClientKey(pub.text.toString().trim())){notify("이 프로젝트의 공개 키를 입력하세요.");return@setPositiveButton}
                api.signIn(pub.text.toString().trim(),email.text.toString().trim(),pass.text.toString(),
                    { transport,session ->
                        val info=transport.rpc(session,"my_context",JSONObject()) as? JSONObject
                        if(info?.optString("role")!="vehicle"||info.optJSONObject("vehicle")==null)
                            throw IllegalArgumentException("Vehicle account required")
                        info
                    }
                ){result,err,_->
                    if(err!=0||result !is JSONObject){notify("공개 키와 차량 단말 계정을 확인하세요. ($err)");return@signIn}
                    generation++;running=false;lastSync=0;memos=emptyMap();failure=""
                    context=null;cursor=null
                    useContext(result)
                    if(!prefs.contains("demo_after:${vehicleId()}"))prefs.edit().putLong("demo_after:${vehicleId()}",System.currentTimeMillis()).commit()
                    notify("차량 계정으로 로그인했어요.")
                    sync()
                }
            }.show()
    }
    private fun useContext(info:JSONObject):Boolean {
        if(info.optString("role")!="vehicle"||info.optJSONObject("vehicle")==null)return false
        val prior=vehicleId()
        context=info
        val v=vehicle()?:return false
        val c=company()?:return false
        if(prior!=vehicleId()){
            cursor=null
        }
        val profile=profileStore.get()
        val next=profile.copy(company=c.string("name"),businessNo=c.string("biz_no"),
            model=if (v.string("id") == com.autologpro.data.LogbookProfile.DEMO_VEHICLE_ID) com.autologpro.data.LogbookProfile.DEFAULT_MODEL
            else v.string("model").ifBlank{profile.model},plate=v.string("plate_no"))
        if(next!=profile)profileStore.save(next)
        return true
    }
    private var pairingDialog: AlertDialog? = null
    private val pairingVehicleId = Regex("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")

    fun closePairingDialog() {
        pairingDialog?.dismiss()
        pairingDialog = null
    }

    private fun openPairingDialog(builder: AlertDialog.Builder): AlertDialog {
        closePairingDialog()
        val dialog = builder.create()
        dialog.setOnDismissListener { if (pairingDialog === dialog) pairingDialog = null }
        pairingDialog = dialog
        dialog.show()
        return dialog
    }

    private fun pairingNotice(message: String, login: Boolean = false, retry: Boolean = false) {
        val prompt = AlertDialog.Builder(host).setTitle("휴대폰 연결 QR").setMessage(message)
        when {
            login -> prompt.setPositiveButton("차량 로그인") { _, _ -> configure() }.setNegativeButton("닫기", null)
            retry -> prompt.setPositiveButton("다시 시도") { _, _ -> invite() }.setNegativeButton("닫기", null)
            else -> prompt.setPositiveButton("확인", null)
        }
        openPairingDialog(prompt)
    }

    fun invite() {
        if (host.isFinishing || host.isDestroyed) return
        if (!parked()) {
            pairingNotice("주차 상태(P)에서 연결 QR을 열 수 있어요. 주차한 뒤 다시 눌러 주세요.")
            return
        }
        if (!enabled) {
            pairingNotice("QR을 만들려면 먼저 이 차량의 단말 계정으로 로그인해 주세요. 휴대폰 직원 계정과는 다른 계정이에요.", login = true)
            return
        }
        val id = vehicleId()
        if (pairingVehicleId.matches(id)) {
            showPairingQr(id)
            return
        }
        val g = generation
        val loading = openPairingDialog(AlertDialog.Builder(host)
            .setTitle("휴대폰 연결 QR")
            .setMessage("서버에서 이 차량 정보를 확인하고 있어요.")
            .setNegativeButton("취소", null))
        api.rpc("my_context", JSONObject()) { result, error, _ ->
            if (g != generation || pairingDialog !== loading || !loading.isShowing ||
                host.isFinishing || host.isDestroyed) return@rpc
            if (!parked()) {
                closePairingDialog()
                return@rpc
            }
            val info = result as? JSONObject
            val serverId = info?.optJSONObject("vehicle")?.string("id").orEmpty()
            when {
                error == 401 || error == 403 || !enabled ->
                    pairingNotice("차량 계정 연결을 확인할 수 없어요. 차량 단말 계정으로 다시 로그인해 주세요.", login = true)
                error != 0 ->
                    pairingNotice("서버에서 차량 정보를 불러오지 못했어요. 인터넷 연결을 확인해 주세요. (오류 $error)", retry = true)
                info == null || !pairingVehicleId.matches(serverId) || !useContext(info) ->
                    pairingNotice("로그인한 계정에 연결된 차량을 찾을 수 없어요. 차량 단말 계정을 확인해 주세요.", login = true)
                else -> showPairingQr(serverId)
            }
        }
    }

    private fun showPairingQr(id: String) {
        if (!parked()) return
        val payload = "autologpro://vehicle/v1/" + id.lowercase(java.util.Locale.ROOT)
        val bitmap = runCatching {
            com.autologpro.data.QrExportManager(isParked = { parked() }).qr(payload, 384)
        }.getOrElse {
            pairingNotice("QR 이미지를 만들지 못했어요. 다시 시도해 주세요.", retry = true)
            return
        }
        val density = host.resources.displayMetrics.density
        val side = minOf((240 * density).toInt(), host.resources.displayMetrics.heightPixels * 40 / 100)
        val image = android.widget.ImageView(host).apply {
            setImageBitmap(bitmap)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(android.graphics.Color.WHITE)
            contentDescription = "휴대폰 연결 QR"
        }
        val panel = android.widget.LinearLayout(host).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            val pad = (10 * density).toInt()
            setPadding(pad, pad, pad, pad)
            addView(image, android.widget.LinearLayout.LayoutParams(side, side))
        }
        openPairingDialog(AlertDialog.Builder(host)
            .setTitle("휴대폰 연결 QR · " + vehicle()?.string("plate_no").orEmpty())
            .setMessage("휴대폰 Auto Log Pro에서 ‘차량 QR 스캔’을 누르세요.")
            .setView(panel).setPositiveButton("닫기", null))
    }
    fun service(){
        if(!parked())return
        val day=field("예약일 · YYYY-MM-DD",prefs.getString("service_date","").orEmpty())
        val shop=field("정비소 이름",prefs.getString("service_shop","").orEmpty())
        AlertDialog.Builder(host).setTitle("정비 입고 일정").setView(stack(day,shop))
            .setNegativeButton("취소",null).setPositiveButton("저장"){_,_->
                if(!parked())return@setPositiveButton
                val d=day.text.toString().trim();val s=shop.text.toString().trim()
                if(runCatching{java.time.LocalDate.parse(d)}.isFailure||s.isBlank()||s.length>60)notify("예약일과 정비소 이름을 확인하세요.")
                else {prefs.edit().putString("service_date",d).putString("service_shop",s).apply();notify("정비 일정을 저장했어요.")}
            }.show()
    }
    fun selectDriver(){
        if(!parked()||!enabled||vehicleId().isEmpty()){notify("주차한 뒤 차량 계정으로 로그인하세요.");return}
        val current=(records(false)+records(true)).filter{it.endAt!=null&&belongs(it)}.sortedByDescending{it.startAt}.take(25)
        val titles=listOf("다음 운행의 운전자 선택")+current.map{"${formatDate(it)} · ${"%.1f".format(it.distanceKm)} km · ${it.driver}"}
        AlertDialog.Builder(host).setTitle("운전자 확인").setItems(titles.toTypedArray()){_,i->chooseDriver(current.getOrNull(i-1))}
            .setNegativeButton("취소",null).show()
    }
    fun chooseDriverForTrip(id:Long){
        if(!parked()||!enabled||vehicleId().isEmpty()){notify("차량 계정 연결 후 선택할 수 있어요.");return}
        val t=repo.coreTrip(id)?.let(CoreTripGateway::toRecord)?:return
        if(!belongs(t))return
        chooseDriver(t)
    }
    private fun chooseDriver(t:TripRecord?){
        api.rpc("vehicle_driver_candidates",JSONObject()){result,err,_->
            if(!parked()||host.isFinishing||host.isDestroyed)return@rpc
            val options=arr(result as? JSONArray)
            if(err!=0||options.isEmpty()){notify("같은 회사의 직원 계정이 있는지 확인하세요. ($err)");return@rpc}
            val labels=options.map{it.string("display_name")+" · "+it.string("department_name")}.toTypedArray()
            AlertDialog.Builder(host).setTitle(if(t==null)"다음 운행 운전자" else "이 운행의 운전자")
                .setItems(labels){_,i->
                    if(!parked())return@setItems
                    val d=options[i]
                    if(t==null){
                        prefs.edit().putString(selectedDriverKey(),d.toString()).commit()
                        notify("다음에 종료되는 운행의 후보로 저장했어요.")
                    }else{
                        prefs.edit().putString(assignmentKey(t),d.toString()).commit()
                        repo.applySyncedDriver(t.id,d.string("display_name"),d.string("department_name"))
                        notify("운전자 변경을 저장했어요.");sync()
                    }
                }.setNegativeButton("취소",null).show()
        }
    }
    fun captureDriver(t:TripRecord){
        if(!enabled||vehicleId().isEmpty()||!belongs(t)||t.endAt==null||entry(t)!=null||prefs.contains(assignmentKey(t)))return
        val chosen=prefs.getString(selectedDriverKey(),null)?:return
        prefs.edit().remove(selectedDriverKey()).putString(assignmentKey(t),chosen).commit()
    }
    fun changed(id:Long,purpose:Purpose,via:String) {
        if(!enabled||vehicleId().isEmpty())return
        val t=repo.coreTrip(id)?.let(CoreTripGateway::toRecord)?:return
        if(t.endAt==null||!belongs(t))return
        val event=JSONObject().put("purpose",slug(purpose)).put("changed_at",now()).put("via",via)
        prefs.edit().putString(uiEventKey(t),event.toString()).commit()
        sync()
    }
    fun saveMemo(id:Long,text:String):Boolean {
        if(!enabled||!parked()||vehicleId().isEmpty()||text.length>200)return false
        val t=repo.coreTrip(id)?.let(CoreTripGateway::toRecord)?:return false
        if(t.endAt==null||!belongs(t)||t.purpose==Purpose.PERSONAL)return false
        val cleaned=text.trim()
        if(cleaned==memo(id)&&pendingMemo(t)==null)return true
        val event=JSONObject().put("memo",cleaned).put("changed_at",now())
        if(!prefs.edit().putString(memoEventKey(t),event.toString()).commit())return false
        memos=if(cleaned.isBlank())memos-id else memos+(id to cleaned)
        sync()
        return true
    }
    private fun belongs(t:TripRecord):Boolean {
        if(t.id>=0||vehicleId().isEmpty())return false
        if(t.id%2L!=0L)return BuildConfig.DEBUG && t.startAt >= prefs.getLong("demo_after:${vehicleId()}",Long.MAX_VALUE)
        val plate=vehicle()?.string("plate_no").orEmpty().filterNot(Char::isWhitespace)
        val stored=t.vehiclePlate.orEmpty().filterNot(Char::isWhitespace)
        val number=company()?.string("biz_no").orEmpty().filter(Char::isDigit)
        val companyMatches=if(number.isEmpty())t.legalCompany.orEmpty()==company()?.string("name")
            else t.businessNo.orEmpty().filter(Char::isDigit)==number
        return plate.isNotEmpty()&&plate==stored&&companyMatches
    }
    private fun serverOdo(km:Double)=java.math.BigDecimal.valueOf(km).setScale(3,java.math.RoundingMode.HALF_UP)
    private fun makeUpload(t:TripRecord):JSONObject?{
        val native=repo.coreTrip(t.id)?:return null
        val end=t.endAt?:return null
        if(t.endOdoKm==null)return null
        val p=pending(t)
        val d=prefs.getString(assignmentKey(t),null)?.let{runCatching{JSONObject(it)}.getOrNull()}
        return JSONObject().put("client_trip_id",key(t)).put("start_time",Instant.ofEpochMilli(t.startAt).toString())
            .put("end_time",Instant.ofEpochMilli(end).toString())
            .put("start_odo_km",serverOdo(t.startOdoKm)).put("end_odo_km",serverOdo(t.endOdoKm))
            .put("source",native.startSource.name)
            .put("record_hash",native.hash).put("prev_hash",native.prevHash)
            .put("start_place",if(t.purpose==Purpose.PERSONAL) "" else native.startPlace.orEmpty())
            .put("end_place",if(t.purpose==Purpose.PERSONAL) "" else t.endPlace)
            .put("purpose",p?.string("purpose")?:slug(t.purpose))
            .put("via",p?.string("via")?:"car_ui")
            .apply{if(p!=null)put("purpose_changed_at",p.string("changed_at"))
                if(d!=null)put("driver_id",d.string("user_id"))}
    }
    private fun remember(t:TripRecord,row:JSONObject){
        val id=row.string("id")
        if(id.isNotEmpty())save(t,JSONObject().put("id",id).put("purpose",row.string("purpose")))
    }
    private fun accept(t:TripRecord,row:JSONObject){
        val end=t.endAt?:return
        val odo=t.endOdoKm?:return
        val sameTime=runCatching{Instant.parse(row.string("end_time")).toEpochMilli()==end&&
            Instant.parse(row.string("start_time")).toEpochMilli()==t.startAt}.getOrDefault(false)
        if(!sameTime || row.string("start_odo_km").toBigDecimalOrNull()?.compareTo(serverOdo(t.startOdoKm))!=0||
            row.string("end_odo_km").toBigDecimalOrNull()?.compareTo(serverOdo(odo))!=0){
            failure="동일 운행 ID의 원본이 달라 확인이 필요해요"
            return
        }
        remember(t,row)
        if(!parked()||host.isDestroyed)return
        val local=repo.coreTrip(t.id)?.let(CoreTripGateway::toRecord)?:return
        if(local.endAt!=t.endAt)return
        val purpose=Purpose.entries.firstOrNull{slug(it)==row.string("purpose")}?:return
        if(local.purpose!=purpose&&pending(t)==null)repo.classify(t.id,purpose)
        val memo=row.string("memo")
        if(pendingMemo(t)==null)
            memos=if(memo.isEmpty()||purpose==Purpose.PERSONAL)memos-t.id else memos+(t.id to memo)
        val driver=row.string("driver_name");val dept=row.string("department_name")
        if(driver.isNotEmpty()&&(driver!=local.driver||dept!=local.driverDepartment)&&!prefs.contains(assignmentKey(t)))
            repo.applySyncedDriver(t.id,driver,dept)
    }
    fun sync(){
        if(!enabled||!parked()||host.isDestroyed)return
        if(running){mustSyncAgain=true;return}
        running=true;failure=""
        val g=generation
        api.rpc("my_context",JSONObject()){value,err,_->
            if(g!=generation)return@rpc
            if(err!=0||value !is JSONObject||!useContext(value)){finish(g,"서버 연결 확인 필요 ($err)");return@rpc}
            if(!parked()){finish(g);return@rpc}
            val all=(records(false)+records(true)).filter{
                val end=it.endAt
                belongs(it)&&end!=null&&(it.id%2L!=0L||entry(it)!=null||System.currentTimeMillis()-end>=300_000L)
            }
            val byKey=all.associateBy(::key)
            val new=all.filter{entry(it)==null}.take(40)
            val changes=all.filter{pending(it)!=null||pendingMemo(it)!=null||prefs.contains(assignmentKey(it))}
            fun edits(index:Int){
                if(g!=generation)return
                if(!parked()||host.isDestroyed){finish(g);return}
                if(index>=changes.size){pull(g,byKey);return}
                val t=changes[index]
                val remote=entry(t)?.string("id").orEmpty()
                if(remote.isBlank()){edits(index+1);return}
                fun assign(){
                    val raw=prefs.getString(assignmentKey(t),null)
                    val driver=raw?.let{runCatching{JSONObject(it).string("user_id")}.getOrNull()}
                    if(driver.isNullOrEmpty()){edits(index+1);return}
                    api.rpc("assign_trip_driver",JSONObject().put("p_trip_id",remote).put("p_driver_id",driver)){row,e,_->
                        if(g!=generation)return@rpc
                        if(e==0&&row is JSONObject){
                            if(prefs.getString(assignmentKey(t),null)==raw)prefs.edit().remove(assignmentKey(t)).commit()
                            accept(t,row)
                        }else failure="운전자 지정 재시도 필요 ($e)"
                        edits(index+1)
                    }
                }
                fun editMemo(){
                    val saved=pendingMemo(t)
                    if(saved==null||!parked()){assign();return}
                    val body=JSONObject().put("p_trip_id",remote).put("p_patch",JSONObject().put("memo",saved.string("memo")))
                        .put("p_changed_at",saved.string("changed_at")).put("p_via","car_ui")
                    api.rpc("update_trip",body){data,e,_->
                        if(g!=generation)return@rpc
                        val row=(data as? JSONObject)?.optJSONObject("trip")
                        if(e==0&&row!=null){
                            if(pendingMemo(t)?.toString()==saved.toString())prefs.edit().remove(memoEventKey(t)).commit()
                            accept(t,row)
                        }else failure="메모 전송 재시도 필요 ($e)"
                        assign()
                    }
                }
                val event=pending(t)
                if(event==null){editMemo();return}
                val body=JSONObject().put("p_trip_id",remote).put("p_patch",JSONObject().put("purpose",event.string("purpose")))
                    .put("p_changed_at",event.string("changed_at")).put("p_via",event.string("via"))
                api.rpc("update_trip",body){out,e,_->
                    if(g!=generation)return@rpc
                    val row=(out as? JSONObject)?.optJSONObject("trip")
                    if(e==0&&row!=null){
                        if(pending(t)?.toString()==event.toString())prefs.edit().remove(uiEventKey(t)).commit()
                        accept(t,row)
                    }else failure="운행 수정 재시도 필요 ($e)"
                    editMemo()
                }
            }
            val bodies=new.mapNotNull{makeUpload(it)}
            if(bodies.isEmpty()){edits(0);return@rpc}
            api.rpc("upload_trips",JSONObject().put("p_trips",JSONArray(bodies))){out,e,_->
                if(g!=generation)return@rpc
                if(e!=0||out !is JSONObject){finish(g,"운행 전송 재시도 필요 ($e)");return@rpc}
                val originals=bodies.associateBy{it.string("client_trip_id")}
                for(result in arr(out.optJSONArray("results"))){
                    val k=result.string("client_trip_id")
                    val t=byKey[k]?:continue
                    val sent=originals[k]?:continue
                    val row=result.optJSONObject("trip")
                    when(result.string("status")){
                        "created","duplicate"->if(row!=null){
                            remember(t,row)
                            if(result.string("status")=="created"){
                                if(pending(t)?.string("changed_at")==sent.string("purpose_changed_at"))
                                    prefs.edit().remove(uiEventKey(t)).commit()
                                val raw=prefs.getString(assignmentKey(t),null)
                                val assigned=raw?.let{runCatching{JSONObject(it).string("user_id")}.getOrNull()}
                                if(assigned!=null&&assigned==sent.string("driver_id"))
                                    prefs.edit().remove(assignmentKey(t)).commit()
                            }
                            accept(t,row)
                        }
                        "duplicate_mismatch"->failure="동일 운행 ID의 원본이 달라 확인이 필요해요"
                        else->failure="일부 운행을 전송하지 못했어요"
                    }
                }
                edits(0)
            }
        }
    }
    private fun pull(g:Int,local:Map<String,TripRecord>){
        val prior=cursor?.string("updated_at")?.takeIf{it.isNotBlank()}
        val overlap=prior?.let{runCatching{Instant.parse(it).minusSeconds(120).toString()}.getOrNull()}
        var latest=cursor
        fun page(since:String?,id:String?){
            if(g!=generation)return
            if(!parked()||host.isDestroyed){finish(g);return}
            val args=JSONObject().put("p_vehicle_id",vehicleId()).put("p_limit",150)
            if(since!=null)args.put("p_since",since)
            if(id!=null)args.put("p_since_id",id)
            api.rpc("sync_trips",args){data,err,_->
                if(g!=generation)return@rpc
                if(err!=0||data !is JSONObject){finish(g,"운행 조회 재시도 필요 ($err)");return@rpc}
                for(row in arr(data.optJSONArray("trips")))local[row.string("client_trip_id")]?.let{accept(it,row)}
                val next=data.optJSONObject("next_cursor")
                if(next!=null&&(prior==null||runCatching{Instant.parse(next.string("updated_at"))>=Instant.parse(prior)}.getOrDefault(false)))latest=next
                if(data.optBoolean("has_more")&&next!=null)page(next.string("updated_at"),next.string("id"))
                else {
                    cursor=latest
                    if(latest!=null)prefs.edit().putString("cursor:${vehicleId()}",latest.toString()).apply()
                    finish(g)
                }
            }
        }
        page(overlap,null)
    }
    private fun finish(g:Int,error:String=""){
        if(g!=generation)return
        running=false;if(error.isNotEmpty())failure=error
        if(error.isEmpty()&&failure.isEmpty())lastSync=System.currentTimeMillis()
        if(!host.isDestroyed)onChange()
        if(mustSyncAgain){mustSyncAgain=false;android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({sync()},500)}
    }
    fun report(year:Int,month:Int,format:String,save:(String,ByteArray,String)->Unit){
        if(!enabled||!parked()||vehicleId().isEmpty()){notify("주차한 뒤 차량 계정으로 로그인하세요.");return}
        val period=java.time.YearMonth.of(year,month)
        val payload=JSONObject().put("action","create").put("vehicle_id",vehicleId()).put("period_start",period.atDay(1).toString())
            .put("period_end",period.atEndOfMonth().toString()).put("format",format)
        val g=generation
        api.function("logbook",payload){data,err,_->
            if(g!=generation||!parked())return@function
            if(err!=0||data !is JSONObject){notify("서버 운행기록부를 만들지 못했어요. ($err)");return@function}
            val name=data.optJSONObject("file")?.string("file_name").orEmpty()
            api.download(data.string("download_url")){bytes,e,_->
                if(g!=generation||!parked())return@download
                if(e==0&&bytes is ByteArray)save(name,bytes,format)
                else notify("운행기록부를 내려받지 못했어요. ($e)")
            }
        }
    }
}
