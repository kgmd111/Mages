package org.mlm.mages.settings

import io.github.mlmgames.settings.core.SettingField
import io.github.mlmgames.settings.core.SettingsSchema
import io.github.mlmgames.settings.core.annotations.SettingPlatform
import io.github.mlmgames.settings.core.platform.currentPlatform
import io.github.mlmgames.settings.core.resources.StringResourceProvider
import io.github.mlmgames.settings.core.resources.resolveString
import kotlin.reflect.KClass

fun <T> SettingsSchema<T>.filteredForSearch(
    query: String,
    provider: StringResourceProvider,
    platform: SettingPlatform = currentPlatform,
): SettingsSchema<T> {
    val terms = query.lowercase().split(' ').filter { it.isNotBlank() }
    if (terms.isEmpty()) return this
    val matches = fields.filter { field ->
        val meta = field.meta ?: return@filter false
        if (!meta.isVisibleOnPlatform(platform)) return@filter false
        val haystack = buildString {
            append(meta.resolvedTitle(provider))
            append(' ')
            append(meta.resolvedDescription(provider))
            append(' ')
            append(categoryTitle(meta.category, provider))
        }.lowercase()
        terms.all { it in haystack }
    }
    return SearchResultSchema(this, matches)
}

private fun <T> SettingsSchema<T>.categoryTitle(
    category: KClass<*>,
    provider: StringResourceProvider,
): String =
    provider.resolveString(
        key = categoryTitleKeys[category].orEmpty(),
        resId = categoryTitleResources[category] ?: 0,
        fallback = category.simpleName.orEmpty(),
    )

private class SearchResultSchema<T>(
    private val base: SettingsSchema<T>,
    override val fields: List<SettingField<T, *>>,
) : SettingsSchema<T> {
    override val default: T get() = base.default
    override val schemaVersion: Int get() = base.schemaVersion
    override val fieldMetadata get() = base.fieldMetadata
    override val categoryTitleResources get() = base.categoryTitleResources
    override val categoryTitleKeys get() = base.categoryTitleKeys

    override fun fieldByName(name: String): SettingField<T, *>? = base.fieldByName(name)
    override fun fieldByKey(key: String): SettingField<T, *>? = base.fieldByKey(key)
    override fun isEnabled(
        model: T,
        field: SettingField<T, *>,
    ): Boolean = base.isEnabled(model, field)
}
