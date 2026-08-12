package me.rerere.rikkahub.learning.jobs

import java.lang.reflect.Modifier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningJobLeaseTest {
    @Test
    fun leaseIsOpaqueAndHasNoCopyOrPublicConstructor() {
        assertTrue(LearningJobLease::class.java.isInterface)
        assertFalse(LearningJobLease::class.java.methods.any { it.name == "copy" })
        assertFalse(
            LearningJobLease::class.java.declaredConstructors.any { constructor ->
                Modifier.isPublic(constructor.modifiers)
            },
        )

        assertTrue(
            LearningJobLease::class.java.methods
                .filterNot { it.declaringClass == Any::class.java }
                .none { method -> method.parameterCount > 0 },
        )
    }
}
