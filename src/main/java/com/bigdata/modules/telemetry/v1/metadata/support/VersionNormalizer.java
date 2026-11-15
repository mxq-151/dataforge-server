package com.bigdata.modules.telemetry.v1.metadata.support;

import com.bigdata.modules.telemetry.v1.metadata.model.NormalizedVersion;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 版本号标准化：只做可验证的解析，解析不出来返回 null，绝不猜测。
 *
 * <ul>
 *   <li>{@code 24.8.14.39} → semver {@code 24.8.14}，build {@code 39}；</li>
 *   <li>{@code doris-4.1.5-release-917b20be838} → semver {@code 4.1.5}，
 *       prerelease {@code release}，build {@code 917b20be838}；</li>
 *   <li>{@code unknown} / {@code v1.2} → null（由调用方标记 VERSION_UNPARSEABLE）。</li>
 * </ul>
 */
@Component
public class VersionNormalizer {

    /** 三段数字 + 可选的第四段及之后（build）或预发布段。 */
    private static final Pattern CORE = Pattern.compile(
            "(?<![0-9])([0-9]+)\\.([0-9]+)\\.([0-9]+)((?:[.-][0-9A-Za-z][0-9A-Za-z.-]*)?)");

    public NormalizedVersion normalize(String rawVersion) {
        if (rawVersion == null || rawVersion.isBlank()) {
            return null;
        }
        Matcher matcher = CORE.matcher(rawVersion.trim());
        if (!matcher.find()) {
            return null;
        }
        String tail = matcher.group(4) == null ? "" : matcher.group(4);
        String prerelease = null;
        String build = null;
        if (tail.startsWith(".")) {
            build = trimEdges(tail.substring(1));
        } else if (tail.startsWith("-")) {
            String rest = tail.substring(1);
            int split = rest.indexOf('-');
            prerelease = trimEdges(split < 0 ? rest : rest.substring(0, split));
            build = split < 0 ? null : trimEdges(rest.substring(split + 1));
        }
        String semver = matcher.group(1) + "." + matcher.group(2) + "." + matcher.group(3);
        return new NormalizedVersion(semver,
                Integer.valueOf(matcher.group(1)),
                Integer.valueOf(matcher.group(2)),
                Integer.valueOf(matcher.group(3)),
                blankToNull(prerelease), blankToNull(build));
    }

    private String trimEdges(String value) {
        String result = value;
        while (result.endsWith(".") || result.endsWith("-")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
