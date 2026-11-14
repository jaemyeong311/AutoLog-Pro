package com.autologpro.voice

import com.autologpro.app.Purpose
import java.text.Normalizer
import java.util.Locale

/** 운전자가 말한 문장을 해석한 결과. */
sealed interface SpokenAnswer {
    data class PurposeAnswer(val purpose: Purpose) : SpokenAnswer
    /** 기존 건너뛰기 버튼과 같다. */
    data object Skip : SpokenAnswer
    /** 목적을 확신할 수 없으면 기존 기록을 바꾸지 않는다. */
    data class Unrecognized(val heard: String) : SpokenAnswer
}

/** Pleos STT의 최종 문장을 기기에서 해석한다. 발화는 저장하지 않는다. */
object SpokenPurposeParser {
    private val endings = setOf("", "요", "이요", "예요", "이에요", "입니다", "였습니다", "이었어요", "였어요")
    private fun forms(vararg bases: String) = bases.flatMap { b -> endings.map { b + it } }.toSet()
    private val skip = forms("건너뛰기", "스킵", "패스", "취소") + setOf(
        "건너뛸게요", "건너뛰어요", "건너뛰어주세요", "건너뛰겠습니다", "패스할게요", "패스해주세요",
        "스킵할게요", "나중에", "나중에할게요", "나중에분류할게요", "넘어가요", "그냥넘어갈게요"
    )

    // 공백을 없앤 인식문에서도 찾되, 반대 목적이나 모호한 문장은 저장 전에 걸러낸다.
    private val commute = Regex("출퇴근|출근(?!부|율|시간표)|퇴근(?!율|시간표)")
    private val personal = Regex(
        "개인용|개인일정|개인용무|개인적인(?:일|용무|이동|일정|볼일|목적|이유|약속|용도)|" +
        "개인볼일|개인업무|비업무|사적인(?:일|이동|용무|볼일|목적|이유|약속|일정)|" +
        "사적용도|사생활|개인(?:미팅|회의|약속|일로)|친구(?:랑|하고|와|과)(?:미팅|약속|만나)|(?<!현|공|시|매|차)장(?:을)?(?:보러|봤)|친구(?:를)?(?:만나|만났)|가족(?:을)?(?:데리러|태우러)"
    )
    private val exactPersonal = forms("개인", "개인용", "비업무", "비업무용", "업무기록제외") +
        setOf("개인적인용무예요", "사적인이동", "업무기록에서빼주세요")
    private val explicitExclusion = Regex("업무기록(?:에서)?(?:빼|제외)(?:주세요|해주세요|할게요|입니다|요)?$")
    private val directBusiness = forms("일반업무", "일반업무용", "업무", "업무용", "비즈니스")
    private val strongBusiness = Regex(
        "일반업무|업무(?:용|인데|이고|도했|때문|차|목적|보러|보고|봤|처리|미팅|관련|상|로|라서|를봤|를보러)|" +
        "외근(?!기록|설정|모드)|출장(?!비|소|마사지|세차|뷔페|요리|기록)|" +
        "거래처|고객사|협력사|파트너사|발주처|납품(?!서|기록)|수금(?!기록)|현장(?:점검|실사|확인|방문|조사|지원|미팅|작업)|" +
        "업체(?:에)?(?:방문|미팅|상담|다녀|들렀)|고객(?:을|과|이랑)?(?:방문|미팅|상담|만나|만났|응대)|(?:영업|판촉|회사)(?:활동|미팅|일정)|" +
        "회사일(?:로|때문|보러|보고|봤)|서류(?:를)?(?:전달|제출|수령)|견적(?:서)?(?:를)?(?:제출|전달|상담)|" +
        "(?:장비|자재|물품)(?:를|을)?(?:납품|배송|수거|인수|픽업)|장애(?:대응|조치)|현장as|as(?:하러|처리|방문|출장)|비즈니스(?:미팅|목적|로)|" +
        "(?:업무|직무)(?:교육|연수)|(?:사업|업무)(?:설명회|행사)|사내교육|외부(?:미팅|회의)|시공현장|공사현장|지점(?:에)?(?:방문|점검|다녀)|(?:현장|공장)(?:에|으로)?(?:다녀|갔|가서)|공장(?:을)?(?:보러|점검|방문)|업무보고|계약(?:을)?(?:체결|미팅|하러)|시장조사|제품(?:을)?(?:설명|시연)|물류센터(?:에)?(?:방문|다녀|들렀)|물건(?:을)?(?:납품|배송)|대리점(?:에)?(?:방문|다녀|들렀)|사무용품(?:을)?(?:구매|사러)|업무설비(?:를)?(?:수리|점검)"
    )
    private val shortBusiness = forms("미팅", "회의", "외근", "출장", "거래처", "고객사", "현장점검", "납품", "수금", "영업", "업무차")
    // '미팅'이나 '회의'가 들어 있다는 이유만으로 장비·기록·설정에 관한 말을 분류하지 않는다.
    private val meetingAction = Regex(
        "(?:미팅|회의|상담|협의|영업|판촉)(?:을|를|에|도|이|가|때문에|이있어서|가있어서|있어서)?" +
        "(?:다녀|갔|갔다|가느라|가려고|가러|다녀오|참석|했|하고|하러|하느라|하려고|보고|봤|마치|마쳤|잡혀|때문|일정으로|차|이었|였)" +
        "|(?:미팅|회의|협의)(?:참석|방문|일정|차|으로|(?:이|가)?있었|(?:이|가)?있어서)"
    )
    private val englishBusiness = Regex("(?:business(?: trip)?|client (?:visit|meeting)|work (?:trip|meeting)|meeting)")
    private val uncertain = Regex(
        "아니(?:에요|예요|요|고|라|었|지)|아닌|아닙니다|말고|(?:안|못)(?:갔|갔다|다녀|했|가서|가요|가고|들렀|만났|봤)|" +
        "(?:하지|가지|들르지|만나지|보지|않았|않아|않습니다|없었|없어요|없습니다)|모르겠|모르죠|모르나요|애매|아마(?!존|추어)|같아요|같습니다|" +
        "일수도|일지도|일까요|인가요|인건가요|되는건가요|맞나요|인가싶|였나|했었나|중에뭐|중에어느|중하나|또는|이거나|거나|" +
        "둘다|두가지|겸해서|겸했|겸사겸사|일수있|거같|예정|하려던|가려다|(?:내일|다음주|다음에)|갔나요|다녀왔나요|갈까요|가도되|가도돼|연락처|전화번호|알려주|어디(?:야|예요|인가|있)|" +
        "(?:취소(?:됐|되었)|분류기준|분류방법|분류설정|저장되나요|기록되나요|기록보여|기록(?:목록|조회)|어떻게분류|무슨뜻|(?:동료|팀장|부장|친구|다른사람)(?:이|가)(?:거래처|고객사|출장|미팅|회의|외근))"
    )
    private val embeddedSkip = Regex("건너뛰|건너뛸|스킵|패스할|패스해|나중에(?:할|분류)|넘어갈")

    fun parse(heard: String): SpokenAnswer {
        val normalized = Normalizer.normalize(heard, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val compact = normalized.filter { it.isLetterOrDigit() }
        val unresolved = SpokenAnswer.Unrecognized(heard)
        if (compact.isEmpty() || '?' in normalized || uncertain.containsMatchIn(compact)) return unresolved
        if (compact in skip) return SpokenAnswer.Skip
        if (embeddedSkip.containsMatchIn(compact)) return unresolved

        val excluded = compact in exactPersonal || explicitExclusion.containsMatchIn(compact)
        val hasPersonalCue = excluded || personal.containsMatchIn(compact)
        val commuting = commute.containsMatchIn(compact)
        // '비업무용'은 내부에 '업무용' 문자열을 포함하지만 별개의 목적이다.
        val workText = compact.replace("비업무용", "").replace("비업무", "")
        val working = workText in directBusiness || workText in shortBusiness || strongBusiness.containsMatchIn(workText) ||
            meetingAction.containsMatchIn(workText) || englishBusiness.matches(normalized.trim().trimEnd('.', '!').replace(Regex("\\s+"), " "))
        // 암시만으로는 업무기록에서 제외하지 않고, 자동 업무 분류만 막는다.
        if (hasPersonalCue && (!excluded || commuting || working)) return unresolved
        val candidates = listOfNotNull(
            Purpose.COMMUTE.takeIf { commuting }, Purpose.PERSONAL.takeIf { excluded }, Purpose.BUSINESS.takeIf { working }
        )
        return candidates.singleOrNull()?.let(SpokenAnswer::PurposeAnswer) ?: unresolved
    }
}
