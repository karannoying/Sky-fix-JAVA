package com.skyfix;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-S1 and NFR-4 — rules about the source itself, checked mechanically rather than by review.
 *
 * <p>Three of CLAUDE.md's hard rules are the kind a person stops noticing after a few hundred
 * commits: no SQL built by string concatenation, no scientific library in {@code src/main}, and no
 * package cycles. Each is asserted here by scanning the tree, so the rule is enforced by the build
 * rather than by memory.
 */
class SourceRulesTest {

    private static final Path MAIN = Path.of("src", "main", "java");
    private static final Path TEST = Path.of("src", "test", "java");

    private static List<Path> javaSources() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    @Test
    @DisplayName("T-S1: no SQL in src/main is built by string concatenation")
    void noConcatenatedSql() throws Exception {
        // A SQL keyword followed by a "+" joined to something that is not another string literal
        // is the signature of a query assembled from parts — which is how a value ends up
        // interpolated instead of bound. Splitting a literal across lines for readability is
        // fine, so the "+" must be followed by a non-literal to count.
        //
        // The match is deliberately CASE-SENSITIVE. SQL in this codebase is written in upper
        // case and user-facing prose in lower case, so "DELETE FROM" is a statement while
        // "cannot delete mission " + id is an error message. Matching case-insensitively would
        // flag every such message and the check would soon be switched off, which is worse than
        // having no check at all.
        Pattern sqlLine = Pattern.compile(
                "\"[^\"]*\\b(SELECT |INSERT INTO |UPDATE |DELETE FROM |WHERE |VALUES )"
                        + "[^\"]*\"\\s*\\+\\s*(?!\\s*\")");

        List<String> violations = new ArrayList<>();
        for (Path file : javaSources()) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                Matcher m = sqlLine.matcher(lines.get(i));
                if (m.find()) {
                    violations.add(file + ":" + (i + 1) + ": " + lines.get(i).strip());
                }
            }
        }
        assertThat(violations)
                .as("SQL must be a constant with bound parameters (CLAUDE.md rule 4)")
                .isEmpty();
    }

    @Test
    @DisplayName("T-S1: every SQL statement reaches the driver through a PreparedStatement")
    void sqlGoesThroughPreparedStatements() throws Exception {
        // The complement of the check above: find files that mention SQL keywords at all, and
        // require that they prepare statements rather than calling Statement.execute* with a
        // query. The two DDL exceptions are called out by name, since CREATE TABLE takes no
        // parameters and cannot be prepared with any.
        List<String> violations = new ArrayList<>();
        Pattern rawExecute = Pattern.compile(
                "\\bstatement\\.(execute|executeQuery|executeUpdate)\\s*\\(\\s*[a-zA-Z\"]",
                Pattern.CASE_INSENSITIVE);

        for (Path file : javaSources()) {
            String name = file.getFileName().toString();
            if (name.equals("SchemaInitializer.java") || name.equals("Database.java")) {
                continue; // DDL and PRAGMA only; both are reviewed below
            }
            String source = Files.readString(file, StandardCharsets.UTF_8);
            if (rawExecute.matcher(source).find()) {
                violations.add(file.toString());
            }
        }
        assertThat(violations)
                .as("only the schema initialiser may execute raw statements, and only DDL")
                .isEmpty();
    }

    @Test
    @DisplayName("T-S1: the two files allowed to run raw statements run only DDL and PRAGMA")
    void rawStatementFilesRunOnlyDdl() throws Exception {
        // These two files may call Statement.execute, because CREATE TABLE and PRAGMA take no
        // parameters and so cannot be prepared with any. What they may NOT do is reach a row
        // through a raw statement: every DML string in them must still go to prepareStatement.
        Pattern rawExecuteArgument = Pattern.compile(
                "\\b(?:statement|s)\\.execute(?:Query|Update)?\\s*\\(\\s*\"([^\"]*)\"",
                Pattern.CASE_INSENSITIVE);

        for (String name : List.of("SchemaInitializer.java", "Database.java")) {
            Path file = javaSources().stream()
                    .filter(p -> p.getFileName().toString().equals(name))
                    .findFirst().orElseThrow();
            String source = Files.readString(file, StandardCharsets.UTF_8);

            Matcher m = rawExecuteArgument.matcher(source);
            while (m.find()) {
                String sql = m.group(1).strip().toUpperCase(Locale.ROOT);
                assertThat(sql)
                        .as("%s executes a raw statement that is not DDL or PRAGMA: %s", name, sql)
                        .matches("^(CREATE|PRAGMA|DROP|ALTER)\\b.*");
            }

            // Any DML that does appear must be handed to prepareStatement.
            for (String dml : List.of("INSERT INTO", "UPDATE ", "DELETE FROM", "SELECT ")) {
                int at = source.indexOf("\"" + dml);
                while (at >= 0) {
                    String preceding = source.substring(Math.max(0, at - 220), at);
                    assertThat(preceding)
                            .as("%s builds a %s statement without preparing it", name, dml.strip())
                            .contains("prepareStatement");
                    at = source.indexOf("\"" + dml, at + 1);
                }
            }
        }
    }

    @Test
    @DisplayName("CLAUDE.md rule 1: no scientific library is imported anywhere in src/main")
    void noScientificLibrariesInMain() throws Exception {
        List<String> banned = List.of(
                "org.apache.commons.math", "org.orekit", "org.hipparchus", "smile.",
                "org.ejml", "org.jblas", "org.nd4j", "weka.", "org.apache.commons.numbers");

        List<String> violations = new ArrayList<>();
        for (Path file : javaSources()) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.strip();
                if (!trimmed.startsWith("import ")) {
                    continue;
                }
                for (String prefix : banned) {
                    if (trimmed.contains(prefix)) {
                        violations.add(file + ": " + trimmed);
                    }
                }
            }
        }
        assertThat(violations)
                .as("every aerospace algorithm is hand-written here (CLAUDE.md rule 1)")
                .isEmpty();
    }

    @Test
    @DisplayName("NFR-4: the dependency direction holds — domain depends on nothing")
    void domainDependsOnNothing() throws Exception {
        List<String> violations = new ArrayList<>();
        for (Path file : javaSources()) {
            String path = file.toString().replace('\\', '/');
            if (!path.contains("/com/skyfix/domain/")) {
                continue;
            }
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.strip();
                if (trimmed.startsWith("import com.skyfix.")
                        && !trimmed.startsWith("import com.skyfix.domain.")) {
                    violations.add(file + ": " + trimmed);
                }
            }
        }
        assertThat(violations).as("domain must not depend on any other SKYFIX package").isEmpty();
    }

    @Test
    @DisplayName("NFR-4: layering is acyclic — core, io and persistence never import upwards")
    void layeringIsAcyclic() throws Exception {
        // cli -> app -> {core.*, estimation, persistence, io} -> domain
        record Layer(String path, List<String> mayNotImport) {
        }
        List<Layer> layers = List.of(
                new Layer("/com/skyfix/core/", List.of("com.skyfix.cli", "com.skyfix.app",
                        "com.skyfix.persistence")),
                new Layer("/com/skyfix/io/", List.of("com.skyfix.cli", "com.skyfix.app",
                        "com.skyfix.persistence")),
                new Layer("/com/skyfix/persistence/", List.of("com.skyfix.cli", "com.skyfix.app")),
                new Layer("/com/skyfix/app/", List.of("com.skyfix.cli")));

        List<String> violations = new ArrayList<>();
        for (Path file : javaSources()) {
            String path = file.toString().replace('\\', '/');
            for (Layer layer : layers) {
                if (!path.contains(layer.path())) {
                    continue;
                }
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String trimmed = line.strip();
                    for (String forbidden : layer.mayNotImport()) {
                        if (trimmed.startsWith("import " + forbidden + ".")) {
                            violations.add(file + ": " + trimmed);
                        }
                    }
                }
            }
        }
        assertThat(violations).as("the dependency graph must stay acyclic (NFR-4)").isEmpty();
    }

    @Test
    @DisplayName("CLAUDE.md rule 2: nothing in src/main opens a network connection")
    void noNetworkAccessInMain() throws Exception {
        List<String> banned = List.of("java.net.URL", "java.net.HttpURLConnection",
                "java.net.Socket", "java.net.http", "javax.net.ssl");

        List<String> violations = new ArrayList<>();
        for (Path file : javaSources()) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.strip();
                if (!trimmed.startsWith("import ")) {
                    continue;
                }
                for (String prefix : banned) {
                    if (trimmed.contains(prefix)) {
                        violations.add(file + ": " + trimmed);
                    }
                }
            }
        }
        assertThat(violations).as("SKYFIX runs entirely offline (CLAUDE.md rule 2)").isEmpty();
    }

    @Test
    @DisplayName("CLAUDE.md rule 5: every verify: and PLACEHOLDER marker is intentional and visible")
    void unverifiedClaimsAreMarkedNotHidden() throws Exception {
        // The rule is not "no unverified claims" — it is that unverified claims carry a literal
        // marker. This test proves the markers exist where they should, so a later reviewer can
        // find every one of them with a grep instead of trusting that none were forgotten.
        List<String> markedFiles = new ArrayList<>();
        for (Path file : javaSources()) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            if (source.contains("verify:") || source.contains("[PLACEHOLDER")) {
                markedFiles.add(file.getFileName().toString());
            }
        }
        // Two known unverified items at this stage: the lifting-gas molar masses and the
        // sounding archive's column layout and terms.
        assertThat(markedFiles)
                .as("unverified claims must carry a literal marker")
                .contains("LiftGas.java", "WyomingSoundingReader.java");

        // And the shipped data files must say plainly what they are.
        String referenceHeader = Files.readString(
                Path.of("data", "reference", "ussa1976.csv"), StandardCharsets.UTF_8);
        assertThat(referenceHeader).contains("verify:").contains("PROVENANCE");

        String sounding = Files.readString(
                Path.of("data", "soundings", "SYNTHETIC_2026-09-14_00Z.txt"),
                StandardCharsets.UTF_8);
        assertThat(sounding.toUpperCase(Locale.ROOT))
                .as("a generated sounding must never be mistakable for an observation")
                .contains("SYNTHETIC").contains("[PLACEHOLDER");
    }

    @Test
    @DisplayName("CLAUDE.md rule 5: every marker in src/main and data has a ledger entry")
    void everyMarkerHasARouteToClearingIt() throws Exception {
        // The test above proves the markers are visible. Visible is not the same as actionable: a
        // bare "verify: check this" leaves the next person to rediscover which document, which
        // table, and what the number ought to come out as. docs/VERIFICATION.md carries that for
        // every marker, and this test is what stops the two drifting apart -- a new marker with no
        // entry fails the build, which is the only way a ledger like this stays true.
        String ledger = Files.readString(Path.of("docs", "VERIFICATION.md"), StandardCharsets.UTF_8);

        List<Path> marked = new ArrayList<>();
        for (Path file : javaSources()) {
            if (hasMarker(file)) {
                marked.add(file);
            }
        }
        try (Stream<Path> files = Files.walk(Path.of("data"))) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                // DS-6's generated telemetry is large, absent on a fresh clone, and carries its own
                // synthetic banner rather than a marker; reading all 11 MB of it proves nothing.
                if (file.toString().contains("truth")) {
                    continue;
                }
                if (hasMarker(file)) {
                    marked.add(file);
                }
            }
        }

        assertThat(marked)
                .as("the scan must find the known markers, or it is not scanning anything")
                .isNotEmpty();

        List<String> unlisted = new ArrayList<>();
        for (Path file : marked) {
            if (!ledger.contains(file.getFileName().toString())) {
                unlisted.add(file.toString());
            }
        }
        assertThat(unlisted)
                .as("every verify:/PLACEHOLDER marker needs an entry in docs/VERIFICATION.md "
                        + "saying which source clears it and what the check is")
                .isEmpty();
    }

    /** Whether a file carries an unverified-claim marker, ignoring files that cannot be read. */
    private static boolean hasMarker(Path file) throws IOException {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (java.io.UncheckedIOException | java.nio.charset.MalformedInputException e) {
            return false; // a binary file carries no marker
        }
        return text.contains("verify:") || text.contains("[PLACEHOLDER");
    }

    @Test
    @DisplayName("Every public type in src/main carries Javadoc")
    void publicTypesAreDocumented() throws Exception {
        List<String> undocumented = new ArrayList<>();
        Pattern publicType = Pattern.compile(
                "^\\s*public\\s+(final\\s+|abstract\\s+|sealed\\s+)?"
                        + "(class|interface|enum|record)\\s+(\\w+)");

        for (Path file : javaSources()) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                Matcher m = publicType.matcher(lines.get(i));
                if (!m.find()) {
                    continue;
                }
                // Walk back over annotations and blank lines looking for the end of a Javadoc.
                boolean documented = false;
                for (int j = i - 1; j >= 0 && j >= i - 4; j--) {
                    String previous = lines.get(j).strip();
                    if (previous.endsWith("*/")) {
                        documented = true;
                        break;
                    }
                    if (!previous.isEmpty() && !previous.startsWith("@")) {
                        break;
                    }
                }
                if (!documented) {
                    undocumented.add(file.getFileName() + ": " + m.group(3));
                }
            }
        }
        assertThat(undocumented)
                .as("every public type needs Javadoc saying what it does and in which units")
                .isEmpty();
    }

    @Test
    @DisplayName("no default-profile test depends on the generated DS-6 telemetry")
    void noDefaultTestReadsGeneratedTelemetry() throws Exception {
        // DS-6's telemetry is produced by `run.sh synth` and deliberately not committed --
        // FR-1.4 guarantees it regenerates byte-identically, and the logs are about 11 MB. A test
        // that reads one therefore passes on a machine that has run synth and fails on a fresh
        // clone, which is precisely the offline guarantee CLAUDE.md's second rule makes.
        //
        // This is not hypothetical: CorruptInputTest read ds6-flight-01.csv for a truncation
        // fixture, passed locally for two commits, and failed both CI runs. The evaluations that
        // legitimately need DS-6 carry @Tag("perf") and are excluded from the default profile, so
        // the rule is scoped to everything else.
        List<Path> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(TEST)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                if (source.contains("data/truth/ds6-flight-") && !source.contains("@Tag(\"perf\")")) {
                    offenders.add(file);
                }
            }
        }
        assertThat(offenders)
                .as("tests reading generated DS-6 telemetry without @Tag(\"perf\"); each would "
                        + "fail on a fresh clone. Build the fixture in the test instead.")
                .isEmpty();
    }
}
