package me.rerere.rikkahub.ui.components.ai

import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchPickerVisibilityTest {
    @Test
    fun `residual built in search flag remains switchable instead of blanking the sheet`() {
        val visibility = searchPickerVisibility(
            Model(modelId = "deepseek-v4-flash", tools = setOf(BuiltInTools.Search)),
        )

        assertEquals(
            SearchPickerVisibility(
                showBuiltInSearchSetting = true,
                showAppSearchSettings = false,
            ),
            visibility,
        )
    }

    @Test
    fun `ordinary non native search model keeps app services visible`() {
        val visibility = searchPickerVisibility(Model(modelId = "deepseek-v4-flash"))

        assertEquals(
            SearchPickerVisibility(
                showBuiltInSearchSetting = false,
                showAppSearchSettings = true,
            ),
            visibility,
        )
    }
}
