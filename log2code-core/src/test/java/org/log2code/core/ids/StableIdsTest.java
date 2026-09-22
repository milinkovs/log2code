package org.log2code.core.ids;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StableIdsTest {

    private static final String CODE_UNIT_NAME = "spring-petclinic-microservices";
    private static final String CODE_UNIT_VERSION = "3858f9c630cf989bb6809a86edf47c2be78dc9f1";
    private static final String FILE_PATH =
        "spring-petclinic-customers-service/src/main/java/org/springframework/samples/petclinic/customers/web/OwnerResource.java";
    private static final String CLASS_FQN = "org.springframework.samples.petclinic.customers.web.OwnerResource";
    private static final String METHOD_SIGNATURE = "save(Owner)";
    private static final String TEMPLATE = "Saving owner {}";

    // Golden values: computed once by running StableIds against the fixed inputs above
    // and pasted here as constants, so an accidental change to the formula fails this test.
    private static final String GOLDEN_STATEMENT_ID = "da2bdae0c0f227916731aec370770514";
    private static final String GOLDEN_LOGICAL_ID = "fa93107b1a1e59803ffb55d40ecbf78c";
    private static final String GOLDEN_FILE_ID = "8f6318c0ec6d7fb45e24182a52b53c9c";
    private static final String GOLDEN_METHOD_ID = "7673562226865ee709711fe839d97602";
    private static final String GOLDEN_TYPE_ID = "3f7edfd4d6909576a5bf472efdf23d8e";
    private static final String GOLDEN_RUN_ID = "b32b0699da81541160771d501bfd7649";
    private static final String GOLDEN_LOG_ID = "dc37f71b0143613a6549a96547a324ad";

    @Test
    void statementIdMatchesGoldenValue() {
        String id = StableIds.statementId(CODE_UNIT_NAME, CODE_UNIT_VERSION, FILE_PATH, CLASS_FQN, METHOD_SIGNATURE, TEMPLATE, 0);
        assertThat(id).isEqualTo(GOLDEN_STATEMENT_ID);
        assertThat(id).hasSize(32).matches("[0-9a-f]{32}");
    }

    @Test
    void logicalIdMatchesGoldenValue() {
        String id = StableIds.logicalId(CODE_UNIT_NAME, FILE_PATH, CLASS_FQN, METHOD_SIGNATURE, TEMPLATE, 0);
        assertThat(id).isEqualTo(GOLDEN_LOGICAL_ID);
    }

    @Test
    void fileIdMatchesGoldenValue() {
        String id = StableIds.fileId(CODE_UNIT_NAME, CODE_UNIT_VERSION, FILE_PATH);
        assertThat(id).isEqualTo(GOLDEN_FILE_ID);
    }

    @Test
    void methodIdMatchesGoldenValue() {
        String id = StableIds.methodId(CODE_UNIT_NAME, CODE_UNIT_VERSION, CLASS_FQN, METHOD_SIGNATURE);
        assertThat(id).isEqualTo(GOLDEN_METHOD_ID);
    }

    @Test
    void typeIdMatchesGoldenValue() {
        String id = StableIds.typeId(CODE_UNIT_NAME, CODE_UNIT_VERSION, CLASS_FQN);
        assertThat(id).isEqualTo(GOLDEN_TYPE_ID);
    }

    @Test
    void runIdMatchesGoldenValue() {
        String id = StableIds.runId("project", CODE_UNIT_NAME, CODE_UNIT_VERSION, "0.1.0-SNAPSHOT");
        assertThat(id).isEqualTo(GOLDEN_RUN_ID);
    }

    @Test
    void logIdMatchesGoldenValue() {
        String id = StableIds.logId("smoke-01", "logs/customers-service.log.gz", 42);
        assertThat(id).isEqualTo(GOLDEN_LOG_ID);
    }

    @Test
    void idsAreDeterministic() {
        String first = StableIds.statementId(CODE_UNIT_NAME, CODE_UNIT_VERSION, FILE_PATH, CLASS_FQN, METHOD_SIGNATURE, TEMPLATE, 0);
        String second = StableIds.statementId(CODE_UNIT_NAME, CODE_UNIT_VERSION, FILE_PATH, CLASS_FQN, METHOD_SIGNATURE, TEMPLATE, 0);
        assertThat(first).isEqualTo(second);
    }

    @Test
    void changingCodeUnitVersionChangesStatementId() {
        String base = StableIds.statementId(CODE_UNIT_NAME, CODE_UNIT_VERSION, FILE_PATH, CLASS_FQN, METHOD_SIGNATURE, TEMPLATE, 0);
        String changed = StableIds.statementId(CODE_UNIT_NAME, "another-commit-sha", FILE_PATH, CLASS_FQN, METHOD_SIGNATURE, TEMPLATE, 0);
        assertThat(changed).isNotEqualTo(base);
    }

    @Test
    void changingFilePathChangesStatementId() {
        String base = StableIds.statementId(CODE_UNIT_NAME, CODE_UNIT_VERSION, FILE_PATH, CLASS_FQN, METHOD_SIGNATURE, TEMPLATE, 0);
        String changed = StableIds.statementId(CODE_UNIT_NAME, CODE_UNIT_VERSION, "other/Path.java", CLASS_FQN, METHOD_SIGNATURE, TEMPLATE, 0);
        assertThat(changed).isNotEqualTo(base);
    }

    @Test
    void changingClassFqnChangesStatementId() {
        String base = StableIds.statementId(CODE_UNIT_NAME, CODE_UNIT_VERSION, FILE_PATH, CLASS_FQN, METHOD_SIGNATURE, TEMPLATE, 0);
        String changed = StableIds.statementId(CODE_UNIT_NAME, CODE_UNIT_VERSION, FILE_PATH, "other.Fqn", METHOD_SIGNATURE, TEMPLATE, 0);
        assertThat(changed).isNotEqualTo(base);
    }

    @Test
    void changingMethodSignatureChangesStatementId() {
        String base = StableIds.statementId(CODE_UNIT_NAME, CODE_UNIT_VERSION, FILE_PATH, CLASS_FQN, METHOD_SIGNATURE, TEMPLATE, 0);
        String changed = StableIds.statementId(CODE_UNIT_NAME, CODE_UNIT_VERSION, FILE_PATH, CLASS_FQN, "otherMethod()", TEMPLATE, 0);
        assertThat(changed).isNotEqualTo(base);
    }

    @Test
    void changingTemplateChangesStatementId() {
        String base = StableIds.statementId(CODE_UNIT_NAME, CODE_UNIT_VERSION, FILE_PATH, CLASS_FQN, METHOD_SIGNATURE, TEMPLATE, 0);
        String changed = StableIds.statementId(CODE_UNIT_NAME, CODE_UNIT_VERSION, FILE_PATH, CLASS_FQN, METHOD_SIGNATURE, "Different template {}", 0);
        assertThat(changed).isNotEqualTo(base);
    }

    @Test
    void changingOrdinalChangesStatementId() {
        String base = StableIds.statementId(CODE_UNIT_NAME, CODE_UNIT_VERSION, FILE_PATH, CLASS_FQN, METHOD_SIGNATURE, TEMPLATE, 0);
        String changed = StableIds.statementId(CODE_UNIT_NAME, CODE_UNIT_VERSION, FILE_PATH, CLASS_FQN, METHOD_SIGNATURE, TEMPLATE, 1);
        assertThat(changed).isNotEqualTo(base);
    }

    @Test
    void logicalIdIgnoresCodeUnitVersion() {
        String id1 = StableIds.logicalId(CODE_UNIT_NAME, FILE_PATH, CLASS_FQN, METHOD_SIGNATURE, TEMPLATE, 0);
        // logicalId has no version parameter, so it stays the same across commits by construction;
        // this documents the intent explicitly rather than relying on the signature alone.
        String id2 = StableIds.logicalId(CODE_UNIT_NAME, FILE_PATH, CLASS_FQN, METHOD_SIGNATURE, TEMPLATE, 0);
        assertThat(id1).isEqualTo(id2).isEqualTo(GOLDEN_LOGICAL_ID);
    }

    @Test
    void nullPartIsEncodedAsEmptyString() {
        assertThat(StableIds.hash("stmt", null, "x")).isEqualTo(StableIds.hash("stmt", "", "x"));
    }

    @Test
    void differentUnitSeparatorPlacementProducesDifferentHash() {
        // "ab", "c" must not collide with "a", "bc" (parts are separated, not concatenated).
        assertThat(StableIds.hash("ab", "c")).isNotEqualTo(StableIds.hash("a", "bc"));
    }
}
