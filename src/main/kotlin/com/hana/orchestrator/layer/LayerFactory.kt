package com.hana.orchestrator.layer

import com.hana.orchestrator.orchestrator.ApprovalGate
import io.ktor.client.*
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * 레이어 관리를 위한 팩토리 객체
 */
object LayerFactory {
    
    /**
     * HTTP 클라이언트 생성
     */
    fun createHttpClient(): HttpClient {
        return HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                })
            }
        }
    }
    
    /**
     * 원격 레이어 생성
     */
    fun createRemoteLayer(baseUrl: String): RemoteLayer {
        return RemoteLayer(baseUrl, createHttpClient())
    }
    
    /**
     * Echo 레이어 생성
     */
    fun createEchoLayer(): EchoLayer {
        return EchoLayer()
    }
    
    /**
     * TextTransformer 레이어 생성
     */
    fun createTextTransformerLayer(): TextTransformerLayer {
        return TextTransformerLayer()
    }
    
    /**
     * TextValidator 레이어 생성
     */
    fun createTextValidatorLayer(): TextValidatorLayer {
        return TextValidatorLayer()
    }
    
    /**
     * LLM 레이어 생성
     */
    fun createLLMLayer(modelSelectionStrategy: com.hana.orchestrator.llm.strategy.ModelSelectionStrategy): LLMLayer {
        return LLMLayer(modelSelectionStrategy)
    }
    
    /**
     * 파일 시스템 레이어 생성
     */
    fun createFileSystemLayer(approvalGate: ApprovalGate? = null): FileSystemLayer {
        return FileSystemLayer(approvalGate)
    }

    /**
     * 빌드 레이어 생성 (Gradle compile/build/clean)
     */
    fun createBuildLayer(projectRoot: java.io.File? = null): BuildLayer {
        return if (projectRoot != null) BuildLayer(projectRoot) else BuildLayer()
    }

    /**
     * Git 레이어 생성 (branch/commit/stash/checkout/diff)
     */
    fun createGitLayer(projectRoot: java.io.File? = null): GitLayer {
        return if (projectRoot != null) GitLayer(projectRoot) else GitLayer()
    }

    /**
     * 개발 레이어 생성 (레이어 패턴 참조, 스캐폴딩, 코드 파일 저장)
     */
    fun createDevelopLayer(projectRoot: java.io.File? = null): DevelopLayer {
        return if (projectRoot != null) DevelopLayer(projectRoot) else DevelopLayer()
    }

    /**
     * 코어 평가 레이어 생성 (rc 후보 비교·평가·적용)
     */
    fun createCoreEvaluationLayer(): CoreEvaluationLayer {
        return CoreEvaluationLayer()
    }

    /**
     * 셸 명령 실행 레이어 생성
     */
    fun createShellLayer(projectRoot: java.io.File? = null): ShellLayer {
        return if (projectRoot != null) ShellLayer(projectRoot) else ShellLayer()
    }

    /**
     * 모든 기본 레이어 생성
     */
    fun createDefaultLayers(
        modelSelectionStrategy: com.hana.orchestrator.llm.strategy.ModelSelectionStrategy? = null,
        approvalGate: ApprovalGate? = null
    ): List<CommonLayerInterface> {
        val layers = mutableListOf<CommonLayerInterface>(
            createEchoLayer(),
            createTextTransformerLayer(),
            createTextValidatorLayer(),
            createFileSystemLayer(approvalGate),
            createBuildLayer(),
            createGitLayer(),
            createDevelopLayer(),
            createShellLayer(),
            createCoreEvaluationLayer()
        )
        
        // LLMLayer는 ModelSelectionStrategy가 필요하므로 선택적으로 추가
        modelSelectionStrategy?.let {
            layers.add(createLLMLayer(it))
        }
        
        return layers
    }
}