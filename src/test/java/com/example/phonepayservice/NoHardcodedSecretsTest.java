package com.example.phonepayservice;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards against secrets creeping back into the source tree.
 * Maven runs tests from the project root, so "src/main" resolves correctly.
 */
class NoHardcodedSecretsTest {

    private static final Path MAIN = Path.of("src", "main");

    // key=value lines whose key looks secret and whose value is a literal (not a ${PLACEHOLDER}, not empty)
    private static final Pattern LITERAL_SECRET_PROPERTY = Pattern.compile(
            "^\\s*[\\w.\\-]*(password|secret|api[-_.]?key|token)[\\w.\\-]*\\s*=\\s*(?!\\$\\{)\\S+",
            Pattern.CASE_INSENSITIVE);

    // A 32+ character run of letters and digits inside a Java string literal looks like an API key.
    private static final Pattern KEY_LIKE_STRING_LITERAL = Pattern.compile("\"[A-Za-z0-9]{32,}\"");

    private List<Path> filesWithExtension(String extension) throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(p -> p.toString().endsWith(extension)).toList();
        }
    }

    @Test
    void propertiesFiles_takeSecretsFromEnvironmentPlaceholders() throws IOException {
        for (Path file : filesWithExtension(".properties")) {
            for (String line : Files.readAllLines(file)) {
                assertTrue(!LITERAL_SECRET_PROPERTY.matcher(line).find(),
                        file + " contains a hardcoded secret. Use ${ENV_VAR} instead: " + line.replaceAll("=.*", "=<hidden>"));
            }
        }
    }

    // The local ".env" file holds the real DB password, so it must never be committed.
    @Test
    void gitignore_excludesTheLocalDotEnvFile() throws IOException {
        List<String> lines = Files.readAllLines(Path.of(".gitignore")).stream().map(String::trim).toList();

        assertTrue(lines.contains(".env"), ".gitignore must contain a line with exactly: .env");
    }

    @Test
    void javaSources_containNoApiKeyLikeStringLiterals() throws IOException {
        for (Path file : filesWithExtension(".java")) {
            String source = Files.readString(file);
            assertTrue(!KEY_LIKE_STRING_LITERAL.matcher(source).find(),
                    file + " contains a string literal that looks like an API key. Inject it with @Value instead.");
        }
    }
}
