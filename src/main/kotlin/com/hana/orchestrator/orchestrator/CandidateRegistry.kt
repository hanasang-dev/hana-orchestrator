package com.hana.orchestrator.orchestrator

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 개발 중인 전략/레이어 후보를 beta → alpha → rc 단계로 추적하는 레지스트리.
 *
 * 파일: .hana/candidates.json
 *
 * 단계 정의:
 * - beta  : 개발 완료, 초기 테스트 단계
 * - alpha : 통합 테스트 통과
 * - rc    : 릴리즈 후보 — CoreEvaluationLayer 평가 대상
 */
object CandidateRegistry {

    enum class Stage { beta, alpha, rc }

    @Serializable
    data class CandidateEntry(
        val name: String,
        val stage: String,
        val snapshotFile: String,
        val createdAt: Long,
        val description: String = ""
    )

    @Serializable
    data class Registry(val candidates: MutableList<CandidateEntry> = mutableListOf())

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val registryFile = File(".hana/candidates.json")

    private fun load(): Registry {
        if (!registryFile.exists()) return Registry()
        return try {
            json.decodeFromString(registryFile.readText())
        } catch (_: Exception) {
            Registry()
        }
    }

    private fun save(registry: Registry) {
        registryFile.parentFile?.mkdirs()
        registryFile.writeText(json.encodeToString(Registry.serializer(), registry))
    }

    /**
     * 소스 파일 스냅샷을 저장하고 레지스트리에 등록한다.
     *
     * @param name          후보 이름 (예: "DefaultReActStrategy", "GreeterLayer")
     * @param stage         단계 (beta / alpha / rc)
     * @param sourceFile    스냅샷을 뜰 원본 파일 경로
     * @param description   변경 내용 요약
     * @return 저장된 스냅샷 경로 또는 에러 메시지
     */
    fun promote(name: String, stage: Stage, sourceFile: File, description: String): String {
        if (!sourceFile.exists()) return "ERROR: 원본 파일이 없습니다: ${sourceFile.path}"

        val timestamp = System.currentTimeMillis()
        val snapshotDir = File(".hana/candidates")
        snapshotDir.mkdirs()
        val snapshotFile = File(snapshotDir, "${name}_${stage}_${timestamp}.kt")
        sourceFile.copyTo(snapshotFile, overwrite = true)

        val registry = load()
        registry.candidates.add(
            CandidateEntry(
                name = name,
                stage = stage.name,
                snapshotFile = snapshotFile.path,
                createdAt = timestamp,
                description = description
            )
        )
        save(registry)
        return snapshotFile.path
    }

    /**
     * 등록된 후보 목록을 반환한다.
     * @param name 필터링할 이름 (null이면 전체)
     */
    fun list(name: String? = null): List<CandidateEntry> {
        val registry = load()
        return if (name == null) registry.candidates
        else registry.candidates.filter { it.name == name }
    }

    /**
     * 특정 스냅샷을 레지스트리에서 제거한다 (파일은 유지).
     */
    fun remove(name: String, stage: Stage): Boolean {
        val registry = load()
        val removed = registry.candidates.removeIf { it.name == name && it.stage == stage.name }
        if (removed) save(registry)
        return removed
    }
}
