const p = require("path");
const fs = require("fs-extra");
const { rootDir, appI18nDir } = require("./utils");

/**
 * @type {import("phrasey").PhraseyHooksHandler}
 */
const hook = {
    onTranslationsBuildFinished: async ({ phrasey, state, log }) => {
        if (phrasey.options.source !== "build") {
            log.info("Skipping post-build due to non-build source");
            return;
        }
        await createTranslationKt(phrasey, state, log);
        await createTranslationsKt(phrasey, state, log);
    },
};

module.exports = hook;

/**
 *
 * @param {import("phrasey").Phrasey} phrasey
 * @param {import("phrasey").PhraseyState} state
 * @param {import("phrasey").PhraseyLogger} log
 */
async function createTranslationsKt(phrasey, state, log) {
    const translations = [...state.getTranslations().values()];
    const sortedTranslations = translations.sort((a, b) =>
        a.locale.display.localeCompare(b.locale.display),
    );
    const content = `
package io.github.zyrouge.symphony.services.i18n

@Suppress("ClassName")
open class _Translations {
    val localeCodes: List<String> = listOf(
${sortedTranslations.map((x) => `        "${x.locale.code}",`).join("\n")}
    )
    val localeDisplayNames: Map<String, String> = mapOf(
${sortedTranslations
    .map((x) => `        "${x.locale.code}" to "${x.locale.display}",`)
    .join("\n")}
    )
    val localeNativeNames: Map<String, String> = mapOf(
${sortedTranslations
    .map((x) => `        "${x.locale.code}" to "${x.locale.native}",`)
    .join("\n")}
    )
}
    `;
    const path = p.join(appI18nDir, "Translations.g.kt");
    await fs.writeFile(path, content);
    log.success(`Generated "${p.relative(rootDir, path)}".`);
}

/**
 *
 * @param {import("phrasey").Phrasey} phrasey
 * @param {import("phrasey").PhraseyState} state
 * @param {import("phrasey").PhraseyLogger} log
 */
async function createTranslationKt(phrasey, state, log) {
    /**
     * @type {string[]}
     */
    const staticKeys = [];
    /**
     * @type {string[]}
     */
    const dynamicKeys = [];

    for (const x of state.getSchema().z.keys) {
        if (x.parameters && x.parameters.length > 0) {
            const params = x.parameters.map((x) => `${x}: String`).join(", ");
            const callArgs = x.parameters.join(", ");
            dynamicKeys.push(
                `    fun ${x.name}(${params}): String = key("${x.name}").format(${callArgs})`,
            );
        } else {
            staticKeys.push(
                `    val ${x.name}: String get() = key("${x.name}")`,
            );
        }
    }

    const content = `
package io.github.zyrouge.symphony.services.i18n

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Suppress("ClassName", "Unused", "PropertyName", "FunctionName")
open class _Translation(private val container: _Container) {
    @Immutable
    @Serializable
    data class _Container(val locale: _Locale, val keys: Map<String, String>)

    @Immutable
    @Serializable
    data class _Locale(
        val display: String,
        val native: String,
        val code: String,
        val direction: String,
    )

    // Keys are looked up by name rather than generated as one constructor
    // parameter each. A @Serializable class with one property per string makes
    // kotlinx.serialization emit a synthetic constructor whose argument list
    // overflows the Dalvik/ART 255-register limit once the schema passes ~245
    // keys, which the verifier rejects at runtime with:
    //   java.lang.VerifyError: Verifier rejected class _Translation$_Keys$$serializer
    // A map keeps the generated code flat, so the schema can grow freely.
    // Falls back to the key name so a missing string can never crash startup.
    private fun key(name: String): String = container.keys[name] ?: name

    val LocaleDisplayName: String get() = container.locale.display
    val LocaleNativeName: String get() = container.locale.native
    val LocaleCode: String get() = container.locale.code
    val LocaleDirection: String get() = container.locale.direction

${staticKeys.join("\n")}

${dynamicKeys.join("\n")}
}
    `;
    const path = p.join(appI18nDir, "Translation.g.kt");
    await fs.writeFile(path, content);
    log.success(`Generated "${p.relative(rootDir, path)}".`);
}
