package com.agenthita.app.consent

/**
 * Gemma Terms of Use text, shown wherever Gemma consent is presented:
 * the combined Terms screen (TermsActivity) and the model-download dialog
 * (DashboardActivity) kept as a fallback for users who accepted the app
 * terms before the two consents were combined.
 */
object GemmaTerms {

    val TEXT = """
        GOOGLE GEMMA TERMS OF USE
        Last Updated: February 21, 2024

        By using or distributing any portion of the Gemma model(s), you agree to be bound by these Terms of Use ("Terms"). If you do not agree to these Terms, do not use the Gemma model(s).

        1. DEFINITIONS
        "Google" means Google LLC. "Gemma" means the machine learning model(s) and any accompanying software, documentation, and other materials made available by Google under these Terms. "Outputs" means any content generated through use of the Gemma model(s).

        2. USE RIGHTS
        Subject to these Terms, Google grants you a non-exclusive, worldwide, non-transferable, non-sublicensable, royalty-free limited license to: (a) use and reproduce the Gemma model(s); (b) distribute the Gemma model(s); and (c) create and distribute derivative works of the Gemma model(s).

        3. DISTRIBUTION
        If you distribute or make available Gemma or any derivative works, you must: (a) include a copy of or a link to these Terms with any distribution; (b) not misrepresent the origin of the model(s); and (c) retain all copyright, patent, trademark, and attribution notices.

        4. ADDITIONAL COMMERCIAL TERMS
        If your business or the organization you represent has total annual gross revenues exceeding ${'$'}10 million USD, you may not use Gemma under these Terms and must contact Google to obtain a separate commercial license.

        5. GEMMA PROHIBITED USE POLICY
        You agree that you will not use, and will not permit others to use, Gemma or its Outputs to:

        (a) Violate any applicable law or regulation, or the rights of any person or entity, including intellectual property rights, privacy rights, or rights of personality;

        (b) Engage in, promote, incite, facilitate, or assist in harassment, abuse, threatening, or bullying of individuals or groups;

        (c) Generate content that is hateful or discriminatory based on race, ethnicity, national origin, religion, sex, gender, sexual orientation, disability, or caste;

        (d) Generate, distribute, or facilitate disinformation, misinformation, or propaganda intended to cause harm or deceive;

        (e) Generate or distribute sexually explicit content, or any content that sexualizes minors;

        (f) Facilitate the sexual exploitation or abuse of minors in any way;

        (g) Develop, assist in developing, or deploy weapons of mass destruction, including biological, chemical, nuclear, or radiological weapons;

        (h) Plan or facilitate attacks on critical infrastructure or public safety systems;

        (i) Create cyberweapons, malicious code, or tools designed to cause significant damage if deployed;

        (j) Infringe, misappropriate, or violate the intellectual property rights of any third party;

        (k) Engage in unauthorized collection, processing, or use of personal data;

        (l) Impersonate any person or entity, or misrepresent your affiliation with any person or entity.

        6. INTELLECTUAL PROPERTY
        Google retains all ownership and intellectual property rights in and to Gemma. Except for the licenses expressly granted in these Terms, Google does not grant you any rights in Google's trademarks, trade names, or service marks.

        7. FEEDBACK
        If you provide Google with feedback, ideas, or suggestions regarding Gemma, you grant Google a perpetual, irrevocable, fully paid-up, royalty-free, worldwide license to use and incorporate that feedback without restriction or obligation to you.

        8. DISCLAIMER OF WARRANTIES
        GEMMA IS PROVIDED "AS IS" WITHOUT WARRANTIES OF ANY KIND, EITHER EXPRESS OR IMPLIED, INCLUDING, WITHOUT LIMITATION, ANY WARRANTIES OF TITLE, NON-INFRINGEMENT, MERCHANTABILITY, OR FITNESS FOR A PARTICULAR PURPOSE. GOOGLE DOES NOT WARRANT THAT THE OUTPUTS WILL BE ACCURATE, COMPLETE, OR SUITABLE FOR ANY PARTICULAR PURPOSE.

        9. LIMITATION OF LIABILITY
        TO THE FULLEST EXTENT PERMITTED BY APPLICABLE LAW, IN NO EVENT WILL GOOGLE OR ITS AFFILIATES, DIRECTORS, OFFICERS, EMPLOYEES, OR AGENTS BE LIABLE FOR ANY INDIRECT, INCIDENTAL, SPECIAL, CONSEQUENTIAL, OR EXEMPLARY DAMAGES ARISING FROM OR IN ANY WAY CONNECTED WITH YOUR ACCESS TO OR USE OF GEMMA, EVEN IF GOOGLE HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH DAMAGES.

        10. INDEMNIFICATION
        You agree to indemnify, defend, and hold harmless Google and its affiliates, directors, officers, employees, and agents from and against any and all claims, liabilities, damages, losses, and expenses (including reasonable attorneys' fees) arising out of or in any way connected with: (a) your access to or use of Gemma; (b) your Outputs; or (c) your violation of these Terms.

        11. TERM AND TERMINATION
        These Terms will remain in effect until terminated. Google may terminate your rights under these Terms immediately and without notice for any breach of these Terms. Upon termination, all licenses granted to you under these Terms will immediately cease.

        12. CHANGES TO TERMS
        Google may update these Terms from time to time at its sole discretion. Your continued use of Gemma after any such changes constitutes your acceptance of the revised Terms.

        13. GOVERNING LAW
        These Terms are governed by and construed in accordance with the laws of the State of Delaware, USA, without regard to its conflict of law provisions.

        Full terms: ai.google.dev/gemma/terms
    """.trimIndent()
}
