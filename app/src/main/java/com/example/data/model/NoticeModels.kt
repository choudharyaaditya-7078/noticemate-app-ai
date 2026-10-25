package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

enum class QuoteGroundingStatus(val labelEn: String, val labelHi: String) {
    VERIFIED_EXACT("Verified Exact Quote", "सत्यापित सटीक उद्धरण"),
    PARTIAL_MATCH("Partial Evidence Match", "आंशिक साक्ष्य मिलान"),
    UNVERIFIED("Unverified — Check Source", "असत्यापित — मूल सूचना जांचें")
}

enum class BilingualDisplayMode(val label: String) {
    SIDE_BY_SIDE("EN + हिंदी"),
    ENGLISH_ONLY("English"),
    HINDI_ONLY("हिंदी")
}

data class ChecklistTaskUi(
    val index: Int,
    val textEn: String,
    val textHi: String,
    val sourceQuote: String,
    val isCompleted: Boolean,
    val groundingStatus: QuoteGroundingStatus
)

data class MissingGapUi(
    val index: Int,
    val descriptionEn: String,
    val descriptionHi: String,
    val followUpQuestionEn: String,
    val followUpQuestionHi: String
)

data class GroundedQaTurn(
    val question: String,
    val answerEn: String,
    val answerHi: String,
    val supportingQuote: String,
    val isAnsweredFromText: Boolean
) {
    fun serialize(): String {
        val safeQ = question.replace("||", " ")
        val safeEn = answerEn.replace("||", " ")
        val safeHi = answerHi.replace("||", " ")
        val safeQuote = supportingQuote.replace("||", " ")
        return "$safeQ||$safeEn||$safeHi||$safeQuote||$isAnsweredFromText"
    }

    companion object {
        fun deserialize(raw: String): GroundedQaTurn {
            val parts = raw.split("||")
            return GroundedQaTurn(
                question = parts.getOrNull(0)?.trim().orEmpty(),
                answerEn = parts.getOrNull(1)?.trim().orEmpty(),
                answerHi = parts.getOrNull(2)?.trim().orEmpty(),
                supportingQuote = parts.getOrNull(3)?.trim().orEmpty(),
                isAnsweredFromText = parts.getOrNull(4)?.trim()?.toBooleanStrictOrNull() ?: false
            )
        }
    }
}

@IgnoreExtraProperties
data class NoticeDocument(
    @DocumentId val id: String = "",
    val userId: String = "",
    val title: String = "",
    val rawNoticeText: String = "",
    val purposeEn: String = "",
    val purposeHi: String = "",
    val purposeQuote: String = "",
    val deadline: String = "",
    val deadlineQuote: String = "",
    val eligibilityEn: String = "",
    val eligibilityHi: String = "",
    val eligibilityQuote: String = "",
    val documentsRequired: String = "",
    val contactDetails: String = "",
    val contactQuote: String = "",
    val checklistEn: List<String> = emptyList(),
    val checklistHi: List<String> = emptyList(),
    val checklistQuotes: List<String> = emptyList(),
    val completedIndices: List<Long> = emptyList(),
    val flaggedMissingEn: List<String> = emptyList(),
    val flaggedMissingHi: List<String> = emptyList(),
    val followUpQuestionsEn: List<String> = emptyList(),
    val followUpQuestionsHi: List<String> = emptyList(),
    val qaEntries: List<String> = emptyList(),
    val groundingScore: Long = 0L,
    val modelUsed: String = "google/gemma-4-31b-it",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

data class SampleNoticeCase(
    val id: String,
    val badge: String,
    val title: String,
    val department: String,
    val rawNoticeText: String,
    val expectedAmbiguityNote: String,
    val prebuiltDocument: NoticeDocument
)

object SampleNotices {
    val ALL: List<SampleNoticeCase> = listOf(
        SampleNoticeCase(
            id = "sample_welfare_scholarship",
            badge = "PITCH DECK SCENARIO · PS 02",
            title = "Second-Year Student Welfare Scholarship",
            department = "Student Welfare Office",
            rawNoticeText = "Applications are invited from eligible second-year students. Submit the application with your college ID and income certificate by 30 November 2026. Contact the Student Welfare Office for details.",
            expectedAmbiguityNote = "Missing minimum marks cutoff, family income ceiling, and online/offline submission mode.",
            prebuiltDocument = NoticeDocument(
                title = "Second-Year Student Welfare Scholarship",
                rawNoticeText = "Applications are invited from eligible second-year students. Submit the application with your college ID and income certificate by 30 November 2026. Contact the Student Welfare Office for details.",
                purposeEn = "Invites scholarship applications from eligible second-year students with required identity and income proof.",
                purposeHi = "पात्र द्वितीय वर्ष के छात्रों से कॉलेज आईडी और आय प्रमाण पत्र के साथ छात्रवृत्ति आवेदन आमंत्रित किए जाते हैं।",
                purposeQuote = "Applications are invited from eligible second-year students.",
                deadline = "30 November 2026",
                deadlineQuote = "Submit the application with your college ID and income certificate by 30 November 2026.",
                eligibilityEn = "Second-year students (specific GPA/marks or income cutoff not stated in notice)",
                eligibilityHi = "द्वितीय वर्ष के छात्र (नोटिस में न्यूनतम अंक या आय सीमा स्पष्ट नहीं है)",
                eligibilityQuote = "Applications are invited from eligible second-year students.",
                documentsRequired = "1. College ID Card\n2. Income Certificate",
                contactDetails = "Student Welfare Office",
                contactQuote = "Contact the Student Welfare Office for details.",
                checklistEn = listOf(
                    "Confirm 2nd-year student standing",
                    "Collect valid College ID and Income Certificate",
                    "Visit Student Welfare Office to confirm submission form & cutoff before 30 Nov 2026"
                ),
                checklistHi = listOf(
                    "द्वितीय वर्ष पात्रता जांचें",
                    "कॉलेज आईडी व आय प्रमाण पत्र जुटाएं",
                    "30 नवंबर 2026 से पहले छात्र कल्याण कार्यालय से संपर्क करें"
                ),
                checklistQuotes = listOf(
                    "Applications are invited from eligible second-year students.",
                    "Submit the application with your college ID and income certificate by 30 November 2026.",
                    "Contact the Student Welfare Office for details."
                ),
                completedIndices = emptyList(),
                flaggedMissingEn = listOf(
                    "Minimum marks / CGPA cutoff is not specified.",
                    "Maximum family income threshold for the income certificate is not stated.",
                    "Submission method (physical counter vs. online portal) and office room/hours are omitted."
                ),
                flaggedMissingHi = listOf(
                    "न्यूनतम अंक / सीजीपीए (CGPA) कटऑफ निर्दिष्ट नहीं है।",
                    "आय प्रमाण पत्र के लिए अधिकतम पारिवारिक आय सीमा नहीं बताई गई है।",
                    "आवेदन जमा करने का तरीका (ऑनलाइन पोर्टल या कार्यालय काउंटर) स्पष्ट नहीं है।"
                ),
                followUpQuestionsEn = listOf(
                    "What is the maximum annual family income limit and minimum CGPA required?",
                    "Where can we obtain the application form, and is submission offline at the Student Welfare Office?"
                ),
                followUpQuestionsHi = listOf(
                    "पात्रता के लिए अधिकतम वार्षिक पारिवारिक आय सीमा और न्यूनतम सीजीपीए क्या है?",
                    "आवेदन पत्र कहाँ से मिलेगा और इसे ऑनलाइन जमा करना है या कार्यालय में?"
                ),
                qaEntries = listOf(
                    GroundedQaTurn(
                        question = "What is the income limit for this scholarship?",
                        answerEn = "Not specified in the notice. The notice requires an 'income certificate' but does not state the maximum family income cutoff. Please verify with the Student Welfare Office.",
                        answerHi = "यह नोटिस में निर्दिष्ट नहीं है। नोटिस में 'आय प्रमाण पत्र' मांगा गया है लेकिन अधिकतम आय सीमा नहीं बताई गई है। कृपया छात्र कल्याण कार्यालय से पुष्टि करें।",
                        supportingQuote = "Submit the application with your college ID and income certificate by 30 November 2026.",
                        isAnsweredFromText = false
                    ).serialize()
                ),
                groundingScore = 100L,
                modelUsed = "google/gemma-4-31b-it (Grounded Verified)"
            )
        ),
        SampleNoticeCase(
            id = "sample_hostel_rebate",
            badge = "AMBIGUOUS POLICY NOTICE",
            title = "Winter Vacation Hostel Mess Rebate Circular",
            department = "Office of the Chief Warden",
            rawNoticeText = "All hostel residents leaving campus for the upcoming winter recess may apply for a mess fee rebate. Submit the signed gate pass and parent consent slip to the Hostel Mess Supervisor before 12 December 2026. Late requests will not be entertained.",
            expectedAmbiguityNote = "Missing minimum consecutive leave days required, daily rebate rate, and parent consent format.",
            prebuiltDocument = NoticeDocument(
                title = "Winter Vacation Hostel Mess Rebate Circular",
                rawNoticeText = "All hostel residents leaving campus for the upcoming winter recess may apply for a mess fee rebate. Submit the signed gate pass and parent consent slip to the Hostel Mess Supervisor before 12 December 2026. Late requests will not be entertained.",
                purposeEn = "Allows hostel residents travelling during winter recess to apply for a mess fee rebate before departure.",
                purposeHi = "शीतकालीन अवकाश में घर जाने वाले हॉस्टल छात्रों को मेस शुल्क छूट (Rebate) के लिए आवेदन करने की सूचना।",
                purposeQuote = "All hostel residents leaving campus for the upcoming winter recess may apply for a mess fee rebate.",
                deadline = "12 December 2026",
                deadlineQuote = "Submit the signed gate pass and parent consent slip to the Hostel Mess Supervisor before 12 December 2026.",
                eligibilityEn = "Hostel residents leaving campus for the upcoming winter recess",
                eligibilityHi = "शीतकालीन अवकाश के लिए कैंपस से बाहर जाने वाले सभी हॉस्टल निवासी छात्र",
                eligibilityQuote = "All hostel residents leaving campus for the upcoming winter recess may apply for a mess fee rebate.",
                documentsRequired = "1. Signed Gate Pass\n2. Parent Consent Slip",
                contactDetails = "Hostel Mess Supervisor",
                contactQuote = "Submit the signed gate pass and parent consent slip to the Hostel Mess Supervisor before 12 December 2026.",
                checklistEn = listOf(
                    "Obtain and complete the hostel gate pass",
                    "Arrange signed parent consent slip",
                    "Submit both documents to the Hostel Mess Supervisor before 12 December 2026"
                ),
                checklistHi = listOf(
                    "हॉस्टल गेट पास प्राप्त करें और हस्ताक्षर करवाएं",
                    "अभिभावक सहमति पर्ची (Parent Consent Slip) तैयार करें",
                    "12 दिसंबर 2026 से पहले हॉस्टल मेस सुपरवाइजर को जमा करें"
                ),
                checklistQuotes = listOf(
                    "Submit the signed gate pass and parent consent slip to the Hostel Mess Supervisor before 12 December 2026.",
                    "Submit the signed gate pass and parent consent slip to the Hostel Mess Supervisor before 12 December 2026.",
                    "Late requests will not be entertained."
                ),
                completedIndices = emptyList(),
                flaggedMissingEn = listOf(
                    "Minimum number of absence days required to qualify for mess rebate is not stated.",
                    "Who must countersign the gate pass (Warden vs. Caretaker) is not specified.",
                    "Whether an email/WhatsApp consent from parents is acceptable is unstated."
                ),
                flaggedMissingHi = listOf(
                    "मेस छूट के लिए न्यूनतम कितने दिनों की छुट्टी आवश्यक है, यह नहीं बताया गया है।",
                    "गेट पास पर किसके हस्ताक्षर (वार्डन या केयरटेकर) होने चाहिए, यह स्पष्ट नहीं है।",
                    "अभिभावक की सहमति ईमेल/व्हाट्सएप से मान्य होगी या नहीं, यह उल्लेखित नहीं है।"
                ),
                followUpQuestionsEn = listOf(
                    "What is the minimum number of vacation days required to claim the mess rebate?",
                    "Is a printed parent email/message accepted as the parent consent slip?"
                ),
                followUpQuestionsHi = listOf(
                    "मेस शुल्क में छूट पाने के लिए न्यूनतम कितने दिनों की अनुपस्थिति आवश्यक है?",
                    "क्या माता-पिता द्वारा भेजा गया ईमेल या संदेश सहमति पर्ची के रूप में मान्य होगा?"
                ),
                qaEntries = emptyList(),
                groundingScore = 100L,
                modelUsed = "google/gemma-4-31b-it (Grounded Verified)"
            )
        ),
        SampleNoticeCase(
            id = "sample_ece_innovation_grant",
            badge = "DEPARTMENT CIRCULAR",
            title = "ECE Student Hardware & AI Prototype Micro-Grant",
            department = "Department of Electronics & Communication Engineering",
            rawNoticeText = "Undergraduate ECE teams building assistive technology or edge-AI prototypes are invited to pitch for departmental hardware seed funding. Submit a two-page abstract and bill of materials (BOM) to the Project Lab Coordinator by 18 November 2026.",
            expectedAmbiguityNote = "Missing team size limits, maximum grant budget cap, pitch date, and submission email/portal.",
            prebuiltDocument = NoticeDocument(
                title = "ECE Student Hardware & AI Prototype Micro-Grant",
                rawNoticeText = "Undergraduate ECE teams building assistive technology or edge-AI prototypes are invited to pitch for departmental hardware seed funding. Submit a two-page abstract and bill of materials (BOM) to the Project Lab Coordinator by 18 November 2026.",
                purposeEn = "Provides departmental hardware seed funding for undergraduate ECE teams building assistive tech or edge-AI prototypes.",
                purposeHi = "सहायक तकनीक (Assistive Tech) या एज-एआई प्रोटोटाइप बनाने वाली ईसीई (ECE) छात्र टीमों को हार्डवेयर सीड फंडिंग प्रदान करना।",
                purposeQuote = "Undergraduate ECE teams building assistive technology or edge-AI prototypes are invited to pitch for departmental hardware seed funding.",
                deadline = "18 November 2026",
                deadlineQuote = "Submit a two-page abstract and bill of materials (BOM) to the Project Lab Coordinator by 18 November 2026.",
                eligibilityEn = "Undergraduate ECE student teams working on assistive technology or edge-AI prototypes",
                eligibilityHi = "सहायक तकनीक या एज-एआई प्रोटोटाइप पर काम करने वाली स्नातक ईसीई (ECE) छात्र टीमें",
                eligibilityQuote = "Undergraduate ECE teams building assistive technology or edge-AI prototypes are invited to pitch for departmental hardware seed funding.",
                documentsRequired = "1. Two-Page Project Abstract\n2. Bill of Materials (BOM)",
                contactDetails = "Project Lab Coordinator (ECE Dept)",
                contactQuote = "Submit a two-page abstract and bill of materials (BOM) to the Project Lab Coordinator by 18 November 2026.",
                checklistEn = listOf(
                    "Form an undergraduate ECE project team",
                    "Draft the two-page prototype abstract and itemized Bill of Materials (BOM)",
                    "Submit documents to the Project Lab Coordinator by 18 November 2026"
                ),
                checklistHi = listOf(
                    "स्नातक ईसीई (ECE) प्रोजेक्ट टीम बनाएं",
                    "दो-पृष्ठ का सारांश (Abstract) और सामग्री सूची (BOM) तैयार करें",
                    "18 नवंबर 2026 तक प्रोजेक्ट लैब समन्वयक को जमा करें"
                ),
                checklistQuotes = listOf(
                    "Undergraduate ECE teams building assistive technology or edge-AI prototypes",
                    "Submit a two-page abstract and bill of materials (BOM)",
                    "to the Project Lab Coordinator by 18 November 2026."
                ),
                completedIndices = emptyList(),
                flaggedMissingEn = listOf(
                    "Maximum funding amount per team (budget cap for BOM) is not mentioned.",
                    "Allowed team size (minimum/maximum members) and cross-year eligibility are omitted.",
                    "Exact submission channel (email address vs. printed copy at lab) is not specified."
                ),
                flaggedMissingHi = listOf(
                    "प्रति टीम अधिकतम अनुदान राशि (BOM बजट सीमा) का उल्लेख नहीं है।",
                    "टीम में सदस्यों की न्यूनतम/अधिकतम संख्या स्पष्ट नहीं है।",
                    "दस्तावेज़ ईमेल से भेजने हैं या लैब में हार्ड कॉपी देनी है, यह नहीं बताया गया है।"
                ),
                followUpQuestionsEn = listOf(
                    "What is the maximum seed grant amount we can request in our Bill of Materials?",
                    "How many students can be on a team, and should the abstract be emailed or submitted in print?"
                ),
                followUpQuestionsHi = listOf(
                    "हम बिल ऑफ मटेरियल्स (BOM) में अधिकतम कितनी राशि की मांग कर सकते हैं?",
                    "एक टीम में कितने छात्र हो सकते हैं और सारांश कहाँ जमा करना है?"
                ),
                qaEntries = emptyList(),
                groundingScore = 100L,
                modelUsed = "google/gemma-4-31b-it (Grounded Verified)"
            )
        ),
        SampleNoticeCase(
            id = "sample_exam_registration",
            badge = "ACADEMIC EXAM NOTICE",
            title = "Odd Semester Supplementary & Backlog Examination Notice",
            department = "Controller of Examinations",
            rawNoticeText = "Students with backlog papers in Odd Semester courses must complete supplementary examination registration by 25 November 2026. Attach the previous grade card copy and fee receipt when submitting the registration form at the Academic Section.",
            expectedAmbiguityNote = "Missing per-paper exam fee amount, payment portal details, and maximum allowed backlog credits.",
            prebuiltDocument = NoticeDocument(
                title = "Odd Semester Supplementary & Backlog Examination Notice",
                rawNoticeText = "Students with backlog papers in Odd Semester courses must complete supplementary examination registration by 25 November 2026. Attach the previous grade card copy and fee receipt when submitting the registration form at the Academic Section.",
                purposeEn = "Registration notice for students clearing backlog papers in Odd Semester supplementary examinations.",
                purposeHi = "विषम सेमेस्टर (Odd Semester) के बैकलॉग विषयों की पूरक परीक्षा के पंजीकरण हेतु सूचना।",
                purposeQuote = "Students with backlog papers in Odd Semester courses must complete supplementary examination registration by 25 November 2026.",
                deadline = "25 November 2026",
                deadlineQuote = "must complete supplementary examination registration by 25 November 2026.",
                eligibilityEn = "Students with backlog papers in Odd Semester courses",
                eligibilityHi = "विषम सेमेस्टर के पाठ्यक्रमों में बैकलॉग वाले छात्र",
                eligibilityQuote = "Students with backlog papers in Odd Semester courses",
                documentsRequired = "1. Supplementary Registration Form\n2. Previous Grade Card Copy\n3. Fee Receipt",
                contactDetails = "Academic Section",
                contactQuote = "when submitting the registration form at the Academic Section.",
                checklistEn = listOf(
                    "Check Odd Semester backlog course codes from your previous grade card",
                    "Pay the supplementary exam fee and print the fee receipt",
                    "Submit registration form, grade card copy, and fee receipt at the Academic Section by 25 Nov 2026"
                ),
                checklistHi = listOf(
                    "पिछले ग्रेड कार्ड से अपने विषम सेमेस्टर बैकलॉग विषयों की जांच करें",
                    "पूरक परीक्षा शुल्क का भुगतान करें और रसीद प्राप्त करें",
                    "25 नवंबर 2026 तक शैक्षणिक अनुभाग (Academic Section) में फॉर्म, ग्रेड कार्ड और रसीद जमा करें"
                ),
                checklistQuotes = listOf(
                    "Students with backlog papers in Odd Semester courses",
                    "Attach the previous grade card copy and fee receipt",
                    "when submitting the registration form at the Academic Section."
                ),
                completedIndices = emptyList(),
                flaggedMissingEn = listOf(
                    "Examination fee amount per backlog paper and bank/portal payment link are missing.",
                    "Maximum number of backlog papers or credits allowed in one semester is not stated.",
                    "Examination schedule / tentative start date is not included."
                ),
                flaggedMissingHi = listOf(
                    "प्रति विषय परीक्षा शुल्क की राशि और भुगतान लिंक नहीं दिया गया है।",
                    "एक बार में अधिकतम कितने बैकलॉग विषयों के लिए आवेदन किया जा सकता है, यह स्पष्ट नहीं है।",
                    "पूरक परीक्षा शुरू होने की संभावित तिथि का उल्लेख नहीं है।"
                ),
                followUpQuestionsEn = listOf(
                    "What is the fee per backlog subject and which portal or bank account should be used?",
                    "Is there a cap on how many backlog papers a student can register for?"
                ),
                followUpQuestionsHi = listOf(
                    "प्रति बैकलॉग विषय शुल्क कितना है और भुगतान किस पोर्टल पर करना है?",
                    "एक छात्र अधिकतम कितने विषयों के लिए पंजीकरण कर सकता है?"
                ),
                qaEntries = emptyList(),
                groundingScore = 100L,
                modelUsed = "google/gemma-4-31b-it (Grounded Verified)"
            )
        )
    )
}
