package com.talkievoca.backend.api

import com.talkievoca.backend.knowledge.KnowledgeIngestionService
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RestController
class KnowledgeController(private val knowledgeService: KnowledgeIngestionService) {
    @PostMapping("/v1/knowledge/pdf", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadPdf(
        @RequestParam file: MultipartFile,
        @RequestParam(required = false) title: String?
    ) = knowledgeService.ingestPdf(file, title)

    @GetMapping("/v1/knowledge/search")
    fun search(@RequestParam q: String) = knowledgeService.search(q)
}
