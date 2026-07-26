import path from "path";
import pico from "picocolors";
import fs from "fs-extra";
import { PhraseyBuilder, PhraseyLogger } from "phrasey";
import { Paths } from "../helpers/paths";

const phraseyConfig = path.join(Paths.rootDir, ".phrasey/config.toml");
const outputDir = path.join(Paths.rootDir, "phrasey-dist");
const githubRepository = process.env.GITHUB_REPOSITORY;
const repositoryUrl = githubRepository
    ? `${process.env.GITHUB_SERVER_URL ?? "https://github.com"}/${githubRepository}`
    : undefined;

const start = async () => {
    const summaryResult = await PhraseyBuilder.summary({
        phrasey: {
            cwd: path.dirname(phraseyConfig),
            log: PhraseyLogger.console(),
        },
        builder: {
            config: {
                file: phraseyConfig,
                format: path.extname(phraseyConfig).slice(1),
            },
        },
    });
    if (!summaryResult.success) {
        const errors = Array.isArray(summaryResult.error)
            ? summaryResult.error
            : [summaryResult.error];
        errors.forEach((x) => console.log(x));
        throw new Error("Phrasey summary failed due to errors");
    }
    const summary = summaryResult.data.json();
    await fs.mkdir(outputDir, { recursive: true });
    const mdPath = path.join(outputDir, `README.md`);
    await fs.writeFile(
        mdPath,
        `
# MAZIKA i18n

> Last updated at ${new Date().toLocaleString()}

${
    repositoryUrl
        ? `Read [Translations Guide](${repositoryUrl}/wiki/Translations-Guide) on how MAZIKA handles localization.`
        : "MAZIKA localization status."
}

| Status | Locale | % Translated |
| --- | --- | --- |
${Object.entries(summary.individual)
    .map(([locale, x]) => {
        const status = x.set.percent === 100 ? "✅" : "⚠️";
        const localeLabel = repositoryUrl
            ? `[\`${locale}\`](${repositoryUrl}/blob/main/i18n/${locale}.toml)`
            : `\`${locale}\``;
        const percentage = `${x.set.percent.toFixed(1)}%`;
        return `| ${status} | ${localeLabel} | ${percentage} |`;
    })
    .join("\n")}
        `.trim(),
    );
    printGenerated(mdPath);

    const translationPercent = Math.floor(
        (summary.full.setCount / summary.full.total) * 100,
    );
    const badgeTranslatedPath = path.join(outputDir, `badge-translated.json`);
    await fs.writeFile(
        badgeTranslatedPath,
        JSON.stringify({
            schemaVersion: 1,
            label: "i18n",
            message: `${translationPercent}%`,
            color: "#328fa8",
        }),
    );
    printGenerated(badgeTranslatedPath);

    const languagesCount = Object.keys(summary.individual).length;
    const badgeLanguagesPath = path.join(outputDir, `badge-languages.json`);
    await fs.writeFile(
        badgeLanguagesPath,
        JSON.stringify({
            schemaVersion: 1,
            label: "i18n languages",
            message: `${languagesCount}`,
            color: "#3279a8",
        }),
    );
    printGenerated(badgeLanguagesPath);

    const keysCount = summary.full.keysCount;
    const badgeStringsPath = path.join(outputDir, `badge-strings.json`);
    await fs.writeFile(
        badgeStringsPath,
        JSON.stringify({
            schemaVersion: 1,
            label: "i18n strings",
            message: `${keysCount}`,
            color: "#3265a8",
        }),
    );
    printGenerated(badgeStringsPath);
};

start();

function printGenerated(value: string) {
    console.log(`Generated ${pico.green(value)}.`);
}
