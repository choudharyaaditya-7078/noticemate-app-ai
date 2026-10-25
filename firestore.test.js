const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function validNoticePayload(userId, nowDate = new Date()) {
  return {
    userId,
    title: "Second-Year Student Welfare Scholarship",
    rawNoticeText:
      "Applications are invited from eligible second-year students. Submit the application with your college ID and income certificate by 30 November 2026. Contact the Student Welfare Office for details.",
    purposeEn: "Apply for the second-year student scholarship.",
    purposeHi: "द्वितीय वर्ष छात्र छात्रवृत्ति के लिए आवेदन करें।",
    purposeQuote: "Applications are invited from eligible second-year students.",
    deadline: "30 November 2026",
    deadlineQuote: "by 30 November 2026.",
    eligibilityEn: "Second-year students",
    eligibilityHi: "द्वितीय वर्ष के छात्र",
    eligibilityQuote: "eligible second-year students.",
    documentsRequired: "College ID, Income Certificate",
    contactDetails: "Student Welfare Office",
    contactQuote: "Contact the Student Welfare Office for details.",
    checklistEn: [
      "Confirm 2nd-year standing",
      "Collect college ID & income certificate",
      "Visit Student Welfare Office",
    ],
    checklistHi: [
      "द्वितीय वर्ष पात्रता जांचें",
      "आईडी व आय प्रमाण पत्र जुटाएं",
      "छात्र कल्याण कार्यालय से संपर्क करें",
    ],
    checklistQuotes: [
      "eligible second-year students.",
      "Submit the application with your college ID and income certificate",
      "Contact the Student Welfare Office for details.",
    ],
    completedIndices: [0],
    flaggedMissingEn: [
      "Minimum marks cutoff not specified",
      "Income threshold not specified",
      "Submission method (online/offline) not specified",
    ],
    flaggedMissingHi: [
      "न्यूनतम अंक कटऑफ निर्दिष्ट नहीं है",
      "आय सीमा निर्दिष्ट नहीं है",
      "आवेदन जमा करने का तरीका स्पष्ट नहीं है",
    ],
    followUpQuestionsEn: ["What is the family income ceiling for eligibility?"],
    followUpQuestionsHi: ["पात्रता के लिए पारिवारिक आय सीमा क्या है?"],
    qaEntries: [],
    groundingScore: 100,
    modelUsed: "google/gemma-4-31b-it",
    createdAt: nowDate,
    updatedAt: nowDate,
  };
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user: cannot read or list notices", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(
    unauthDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("notices")
      .where("userId", "==", ALICE_UID)
      .get()
  );
});

test("Authenticated user: can create and read own valid notice", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const docRef = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("notices")
    .doc("notice_1");
  await assertSucceeds(docRef.set(validNoticePayload(ALICE_UID)));
  await assertSucceeds(docRef.get());
});

test("Authenticated user: cannot read or list another user's notices", async () => {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context
      .firestore()
      .collection("users")
      .doc(BOB_UID)
      .collection("notices")
      .doc("bob_notice")
      .set(validNoticePayload(BOB_UID));
  });

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertFails(
    aliceDb
      .collection("users")
      .doc(BOB_UID)
      .collection("notices")
      .doc("bob_notice")
      .get()
  );
  await assertFails(
    aliceDb
      .collection("users")
      .doc(BOB_UID)
      .collection("notices")
      .where("userId", "==", BOB_UID)
      .get()
  );
});

test("Authenticated user: can query own notices with aligned userId filter", async () => {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context
      .firestore()
      .collection("users")
      .doc(ALICE_UID)
      .collection("notices")
      .doc("alice_notice")
      .set(validNoticePayload(ALICE_UID));
  });

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertSucceeds(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("notices")
      .where("userId", "==", ALICE_UID)
      .get()
  );
});

test("Shadow update with ghost field or invalid groundingScore is rejected", async () => {
  const createdAt = new Date(Date.now() - 5000);
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context
      .firestore()
      .collection("users")
      .doc(ALICE_UID)
      .collection("notices")
      .doc("alice_notice")
      .set(validNoticePayload(ALICE_UID, createdAt));
  });

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const docRef = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("notices")
    .doc("alice_notice");

  // Ghost field rejected
  await assertFails(
    docRef.update({
      isAdminVerified: true,
      updatedAt: new Date(),
    })
  );

  // Invalid groundingScore > 100 rejected
  await assertFails(
    docRef.update({
      groundingScore: 150,
      updatedAt: new Date(),
    })
  );

  // Valid checklist completion update succeeds
  await assertSucceeds(
    docRef.update({
      completedIndices: [0, 1],
      updatedAt: new Date(),
    })
  );
});
