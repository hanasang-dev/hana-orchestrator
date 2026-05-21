package com.hana.orchestrator.layer

import java.io.File
import java.net.URLClassLoader

/**
 * 런타임 핫로드 공유 유틸 — child-first ClassLoader 생성.
 *
 * child-first: 지정 prefix 클래스를 buildDir에서 먼저 탐색.
 * 재컴파일 후 최신 버전 로드 보장. 공유 인터페이스는 parent 위임 → ClassCastException 방지.
 *
 * @param buildDir 빌드 출력 디렉토리 (build/classes/kotlin/main)
 * @param prefix   child-first 탐색할 클래스명 접두사 (FQCN)
 * @param parent   부모 ClassLoader
 */
internal fun childFirstClassLoader(buildDir: File, prefix: String, parent: ClassLoader): URLClassLoader {
    return object : URLClassLoader(arrayOf(buildDir.toURI().toURL()), parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name.startsWith(prefix)) {
                synchronized(getClassLoadingLock(name)) {
                    findLoadedClass(name)?.let { return it }
                    try {
                        return findClass(name).also { if (resolve) resolveClass(it) }
                    } catch (_: ClassNotFoundException) { }
                }
            }
            return super.loadClass(name, resolve)
        }
    }
}
