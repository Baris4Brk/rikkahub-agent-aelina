package me.rerere.rikkahub.learning.eval

/**
 * Content-free identity of the runtime which observed the P5 logical performance counters.
 *
 * OS/JVM fields are read from the running process. Build inputs are supplied independently by the
 * pinned CI job; absent inputs remain UNBOUND and therefore cannot accidentally match a reviewed
 * baseline. Only [digestSha256] crosses the redacted artifact boundary.
 */
data class ProductionEvalRuntimeEnvironment(
    val osFamily: String,
    val architecture: String,
    val javaVendorFamily: String,
    val javaMajorVersion: String,
    val javaVmFamily: String,
    val ciProfile: String,
    val gradleVersion: String,
    val androidGradlePluginVersion: String,
    val kotlinVersion: String,
    val jvmTarget: String,
    val compileSdk: String,
    val gateTask: String,
    val frozenMatchRequired: Boolean,
) {
    init {
        canonicalFields().forEach { field ->
            require(field.matches(CANONICAL_FIELD)) { "Non-canonical environment field" }
        }
    }

    val digestSha256: String = EvalDigest.sha256(
        DOMAIN,
        listOf(
            SCHEMA_VERSION,
            "os=$osFamily",
            "arch=$architecture",
            "java_vendor=$javaVendorFamily",
            "java_major=$javaMajorVersion",
            "java_vm=$javaVmFamily",
            "ci_profile=$ciProfile",
            "gradle=$gradleVersion",
            "agp=$androidGradlePluginVersion",
            "kotlin=$kotlinVersion",
            "jvm_target=$jvmTarget",
            "compile_sdk=$compileSdk",
            "gate_task=$gateTask",
            "frozen_match_required=$frozenMatchRequired",
            COUNTER_SEMANTICS,
        ),
    )

    val hasExplicitBuildBinding: Boolean
        get() = listOf(
            ciProfile,
            gradleVersion,
            androidGradlePluginVersion,
            kotlinVersion,
            jvmTarget,
            compileSdk,
            gateTask,
        ).none { it == UNBOUND }

    private fun canonicalFields(): List<String> = listOf(
        osFamily,
        architecture,
        javaVendorFamily,
        javaMajorVersion,
        javaVmFamily,
        ciProfile,
        gradleVersion,
        androidGradlePluginVersion,
        kotlinVersion,
        jvmTarget,
        compileSdk,
        gateTask,
    )

    companion object {
        const val SCHEMA_VERSION: String = "p5-runtime-environment-v2"
        const val COUNTER_SEMANTICS: String =
            "deterministic-operation-and-logical-allocation-units-v1"
        const val UNBOUND: String = "unbound"

        const val CI_PROFILE_ENV: String = "RIKKAHUB_P5_CI_PROFILE"
        const val GRADLE_VERSION_ENV: String = "RIKKAHUB_P5_GRADLE_VERSION"
        const val AGP_VERSION_ENV: String = "RIKKAHUB_P5_AGP_VERSION"
        const val KOTLIN_VERSION_ENV: String = "RIKKAHUB_P5_KOTLIN_VERSION"
        const val JVM_TARGET_ENV: String = "RIKKAHUB_P5_JVM_TARGET"
        const val COMPILE_SDK_ENV: String = "RIKKAHUB_P5_COMPILE_SDK"
        const val GATE_TASK_ENV: String = "RIKKAHUB_P5_GATE_TASK"
        const val REQUIRE_FROZEN_ENV: String = "RIKKAHUB_P5_REQUIRE_FROZEN_ENVIRONMENT"

        /** Capture actual process facts plus explicitly supplied build inputs. */
        fun capture(): ProductionEvalRuntimeEnvironment = capture(
            property = { key -> System.getProperty(key) },
            environment = { key -> System.getenv(key) },
        )

        internal fun capture(
            property: (String) -> String?,
            environment: (String) -> String?,
        ): ProductionEvalRuntimeEnvironment = ProductionEvalRuntimeEnvironment(
            osFamily = normalizeOs(read(property, "os.name")),
            architecture = normalizeArchitecture(read(property, "os.arch")),
            javaVendorFamily = normalizeJavaVendor(read(property, "java.vendor")),
            javaMajorVersion = normalizeJavaMajor(read(property, "java.specification.version")),
            javaVmFamily = normalizeJavaVm(read(property, "java.vm.name")),
            ciProfile = binding(environment, CI_PROFILE_ENV),
            gradleVersion = binding(environment, GRADLE_VERSION_ENV),
            androidGradlePluginVersion = binding(environment, AGP_VERSION_ENV),
            kotlinVersion = binding(environment, KOTLIN_VERSION_ENV),
            jvmTarget = binding(environment, JVM_TARGET_ENV),
            compileSdk = binding(environment, COMPILE_SDK_ENV),
            gateTask = binding(environment, GATE_TASK_ENV),
            frozenMatchRequired = read(environment, REQUIRE_FROZEN_ENV)
                .equals("true", ignoreCase = true),
        )

        private fun normalizeOs(raw: String): String = when {
            raw.contains("linux", ignoreCase = true) -> "linux"
            raw.contains("windows", ignoreCase = true) -> "windows"
            raw.contains("mac", ignoreCase = true) || raw.contains("darwin", ignoreCase = true) ->
                "macos"
            raw.contains("android", ignoreCase = true) -> "android"
            else -> "other"
        }

        private fun normalizeArchitecture(raw: String): String = when (raw.lowercase()) {
            "amd64", "x86_64" -> "x86_64"
            "aarch64", "arm64-v8a", "arm64" -> "aarch64"
            "x86", "i386", "i686" -> "x86"
            else -> "other"
        }

        private fun normalizeJavaVendor(raw: String): String = when {
            raw.contains("adoptium", ignoreCase = true) ||
                raw.contains("temurin", ignoreCase = true) -> "eclipse-adoptium"
            raw.contains("jetbrains", ignoreCase = true) -> "jetbrains"
            raw.contains("android", ignoreCase = true) -> "android"
            raw.contains("oracle", ignoreCase = true) -> "oracle"
            else -> "other"
        }

        private fun normalizeJavaMajor(raw: String): String {
            val normalized = if (raw.startsWith("1.")) raw.substring(2) else raw
            return normalized.takeWhile(Char::isDigit).ifEmpty { "other" }
        }

        private fun normalizeJavaVm(raw: String): String = when {
            raw.contains("dalvik", ignoreCase = true) || raw.contains("art", ignoreCase = true) ->
                "android-art"
            raw.contains("openjdk", ignoreCase = true) &&
                raw.contains("server", ignoreCase = true) -> "openjdk-64-server"
            raw.contains("hotspot", ignoreCase = true) -> "hotspot"
            else -> "other"
        }

        private fun binding(reader: (String) -> String?, key: String): String =
            read(reader, key).lowercase().takeIf { it.matches(CANONICAL_FIELD) } ?: UNBOUND

        private fun read(reader: (String) -> String?, key: String): String =
            runCatching { reader(key)?.trim().orEmpty() }.getOrDefault("")

        private const val DOMAIN = "p5-production-eval-runtime-environment-v2"
        private val CANONICAL_FIELD = Regex("[a-z0-9][a-z0-9._+-]{0,95}")
    }
}
