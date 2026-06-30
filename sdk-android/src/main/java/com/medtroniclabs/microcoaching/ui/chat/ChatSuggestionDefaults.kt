package com.medtroniclabs.microcoaching.ui.chat

/**
 * Curated chat suggestion chips shown above the input bar.
 *
 * Each entry carries both the English display text ([SuggestedQuestion.question])
 * and the Bangla equivalent ([SuggestedQuestion.banglaQuestion]). The chat
 * screen picks between them based on [com.medtroniclabs.microcoaching.Language].
 *
 * To switch to module-sourced dynamic suggestions (sampled from cached quiz
 * questions), replace [ChatViewModel.loadSuggestions] to call
 * [ChatSuggestionsRepository.nextBatch] instead. The repository is kept alive
 * for that purpose.
 */
object ChatSuggestionDefaults {

    val all: List<SuggestedQuestion> = listOf(
        SuggestedQuestion(
            question = "Are 25 breaths per minute normal for a newborn?",
            banglaQuestion = "নবজাতকের জন্য প্রতি মিনিটে ২৫টি শ্বাস কি স্বাভাবিক?",
        ),
        SuggestedQuestion(
            question = "What are the danger signs to refer a pregnant woman?",
            banglaQuestion = "গর্ভবতী মহিলাকে রেফার করার জন্য বিপদ চিহ্নগুলো কী কী?",
        ),
        SuggestedQuestion(
            question = "How do I counsel a mother on exclusive breastfeeding?",
            banglaQuestion = "কীভাবে একজন মাকে শুধুমাত্র বুকের দুধ পান করানো সম্পর্কে পরামর্শ দেব?",
        ),
        SuggestedQuestion(
            question = "What should I advise to a PW with Low BP 90/60?",
            banglaQuestion = "নিম্ন রক্তচাপ ৯০/৬০ সহ গর্ভবতী মহিলাকে কী পরামর্শ দেব?",
        ),
        SuggestedQuestion(
            question = "How can Breast Engorgement and Pain be managed?",
            banglaQuestion = "স্তন ফোলা ও ব্যথা কীভাবে নিয়ন্ত্রণ করা যায়?",
        ),
    )
}
