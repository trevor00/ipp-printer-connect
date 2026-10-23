package kr.ac.hit.networkprinterstudio

/** Printer-reported state; acceptance and a completed physical sheet are separate observations. */
internal data class IppJobStatus(
    val state: Int,
    val reasons: List<String>,
    val message: String?,
    val impressionsCompleted: Int?,
) {
    val terminal: Boolean get() = state in 7..9
    val hasError: Boolean get() = state in 7..8 || reasons.any {
        it.contains("completed-with-errors") || it.contains("completed-with-warnings")
    }
    val label: String get() = when (state) {
        3 -> "인쇄 대기"
        4 -> "작업 보류"
        5 -> "인쇄 처리 중"
        6 -> "인쇄 일시 중지"
        7 -> "프린터에서 작업이 취소되었습니다"
        8 -> "프린터에서 작업이 중단되었습니다"
        9 -> if (hasError) "프린터가 경고·오류와 함께 작업을 완료했습니다" else "프린터 보고: 출력 완료"
        else -> "작업 상태를 확인할 수 없습니다"
    }

    companion object {
        fun from(attributes: Map<String, List<Any?>>): IppJobStatus {
            val state = attributes["job-state"]?.firstOrNull() as? Int
            require(state != null && state in 3..9) { "프린터가 작업 상태를 알려주지 않았습니다" }
            return IppJobStatus(
                state,
                attributes["job-state-reasons"].orEmpty().filterIsInstance<String>().filter {
                    it != "none" && it != "job-completed-successfully" && it != "completed-successfully"
                },
                (attributes["job-state-message"]?.firstOrNull() as? String)?.takeIf { it.isNotBlank() },
                (attributes["job-impressions-completed"]?.firstOrNull() as? Int)?.takeIf { it >= 0 },
            )
        }
    }
}
