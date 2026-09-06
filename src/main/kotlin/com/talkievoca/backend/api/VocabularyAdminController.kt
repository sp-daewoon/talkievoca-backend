package com.talkievoca.backend.api

import com.talkievoca.backend.search.OpenSearchVocabularyIndexer
import com.talkievoca.backend.search.MeaningSearchService
import com.talkievoca.backend.vocabulary.VocabularyCorrection
import com.talkievoca.backend.vocabulary.VocabularyService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/admin/vocabulary")
class VocabularyAdminController(
    private val vocabulary: VocabularyService,
    private val search: OpenSearchVocabularyIndexer,
    private val meanings: MeaningSearchService
) {
    @GetMapping
    fun list(@RequestParam(defaultValue = "pending") status: String) = vocabulary.reviewItems(status)

    @GetMapping("/search")
    fun semanticSearch(@RequestParam q: String) = meanings.search(q)

    @PostMapping("/reindex")
    fun reindex() = meanings.rebuild()

    @PostMapping("/corrections")
    fun correct(@RequestBody correction: VocabularyCorrection) = vocabulary.correct(correction).also {
        search.indexVocabulary(vocabulary.feedbackFor(it.word))
    }

    @DeleteMapping("/{word}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun reject(@PathVariable word: String) = vocabulary.rejectPending(word)
}

@Controller
class VocabularyAdminPageController {
    @GetMapping(value = ["/admin", "/admin/"])
    fun page() = "forward:/admin/index.html"
}
