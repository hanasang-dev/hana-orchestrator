package com.hana.orchestrator.layer

/**
 * 오케스트레이터 위임 레이어 — 서브 목표를 새 작업 흐름으로 실행.
 *
 * SRP: 목표 위임만 담당. LLM 직접 호출 없음.
 * DIP: GoalExecutor 인터페이스에만 의존.
 *
 * ━━━ 사용 시나리오 ━━━
* 외부 작업 흐름이 "이 목표는 복잡하다"고 판단할 때 runGoal()으로 위임.
* 내부적으로 새 작업 흐름이 스폰되어 목표를 처리하고 결과를 반환.
* 외부 흐름 입장에서는 단순 레이어 함수 호출과 동일.
 */
@Layer
class AgentLayer : CommonLayerInterface {

    private lateinit var goalExecutor: GoalExecutor

    /** LayerManager에서 주입 — ReactiveExecutor를 GoalExecutor 람다로 감싸서 전달 */
    fun setGoalExecutor(executor: GoalExecutor) {
        goalExecutor = executor
    }

    /**
     * 서브 목표를 새 ReAct 루프로 실행하고 결과 반환.
     *
    * @param goal 달성할 목표. 자연어 서술 가능. 예: "목표를 개선하고 컴파일 확인해줘"
     */
    @LayerFunction
    suspend fun runGoal(goal: String): String {
        if (!::goalExecutor.isInitialized) return "ERROR: AgentLayer가 초기화되지 않았습니다 (goalExecutor 미주입)"
        return goalExecutor.execute(goal)
    }

    override suspend fun describe(): LayerDescription = AgentLayer_Description.layerDescription

    override suspend fun execute(function: String, args: Map<String, Any>): String = when (function) {
        "runGoal" -> runGoal(args["goal"]?.toString() ?: "")
        else -> throw IllegalArgumentException("Unknown function: $function. Available: runGoal")
    }
}