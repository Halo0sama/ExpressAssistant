package com.halo.expressassistant.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiProfileTest {
    private fun withStore(test: (Context) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefsName = "ai-profile-test-" + java.util.UUID.randomUUID()
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) =
                base.getSharedPreferences(prefsName, mode)
        }
        try { test(context) } finally { base.deleteSharedPreferences(prefsName) }
    }

    @Test fun legacyCustomEndpointModelAndKeySurviveMigration() = withStore { context ->
        context.getSharedPreferences("test", 0).edit()
            .putString("ai_base", "https://custom.invalid/v1")
            .putString("ai_model", "custom-model")
            .putString("ai_key", "sample-credential").commit()
        assertEquals("https://custom.invalid/v1", Store.aiBase(context))
        assertEquals("custom-model", Store.aiModel(context))
        assertEquals("sample-credential", Store.aiKey(context))
        assertEquals(2, Store.aiProfileList(context).size)
    }

    @Test fun providerProfilesSurviveV1Migration() = withStore { context ->
        context.getSharedPreferences("test", 0).edit().putString("ai_profiles",
            """{"qwen":{"base":"https://custom.invalid/v1","key":"sample-credential","model":"custom-model"}}""").commit()
        assertEquals("qwen", Store.activeProfile(context).id)
        assertEquals("custom-model", Store.aiModel(context))
        assertEquals("sample-credential", Store.aiKey(context))
    }

    @Test fun emptyAndCorruptListsHaveUsableFallbacks() = withStore { context ->
        for (value in listOf("[]", "not-json")) {
            context.getSharedPreferences("test", 0).edit().putString("ai_profiles_v2", value).commit()
            assertTrue(Store.aiProfileList(context).isNotEmpty())
            assertTrue(Store.activeProfile(context).model.isNotBlank())
        }
    }

    @Test fun deletingLastProfileDoesNotCrashOrRestoreDeletedCredential() = withStore { context ->
        val only = Store.AiProfile("only", "custom", "Only", "https://custom.invalid/v1", "sample-credential", "custom-model")
        Store.aiProfileList(context).forEach { Store.deleteProfile(context, it.id) }
        context.getSharedPreferences("test", 0).edit()
            .putString("ai_profiles_v2", """[{"id":"only","provider":"custom","label":"Only","base":"https://custom.invalid/v1","key":"sample-credential","model":"custom-model"}]""")
            .putString("ai_active_profile", only.id)
            .putString("ai_key", only.key).commit()
        assertTrue(Store.deleteProfile(context, only.id))
        assertTrue(Store.aiProfileList(context).isNotEmpty())
        assertTrue(Store.aiProfileList(context).none { it.id == only.id })
        assertEquals("", Store.aiKey(context))
        assertEquals("", context.getSharedPreferences("test", 0).getString("ai_key", ""))
    }

    @Test fun editingInactiveProfileKeepsActiveConfigAndMemory() = withStore { context ->
        val active = Store.activeProfile(context)
        Store.upsertProfile(context, active.copy(key = "active-sample"))
        Store.addAiMemory(context, "Test preference")
        val other = Store.AiProfile("other", "custom", "Other", "https://other.invalid/v1", "other-sample", "other-model")
        Store.upsertProfile(context, other)
        assertEquals("active-sample", Store.aiKey(context))
        Store.setActiveProfile(context, other.id)
        assertEquals(other.base, Store.aiBase(context))
        assertEquals(other.key, Store.aiKey(context))
        assertEquals(other.model, Store.aiModel(context))
        assertEquals(listOf("Test preference"), Store.aiMemory(context))
    }

    @Test fun deletingActiveProfileSelectsRemainingConfiguration() = withStore { context ->
        val active = Store.activeProfile(context)
        val remaining = Store.aiProfileList(context).first { it.id != active.id }
        assertTrue(Store.deleteProfile(context, active.id))
        assertEquals(remaining.id, Store.activeProfile(context).id)
    }
}
