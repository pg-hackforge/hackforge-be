package com.popogonry.hackforge.labsession.controller;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.popogonry.hackforge.labsession.dto.LabFileEntry;
import com.popogonry.hackforge.labsession.dto.LabFileResponse;
import com.popogonry.hackforge.labsession.dto.SaveLabFileRequest;
import com.popogonry.hackforge.labsession.service.LabFileService;

import lombok.RequiredArgsConstructor;

/** 웹 IDE 파일 API(ADR 024). 경로에 {@code /}가 들어가서 쿼리로 받는다 */
@RestController
@RequiredArgsConstructor
class LabFileController {

	private final LabFileService labFileService;

	@GetMapping("/api/labs/{id}/files")
	List<LabFileEntry> listLabFiles(@PathVariable long id, Authentication authentication) {
		return labFileService.listLabFiles(id, userId(authentication));
	}

	@GetMapping("/api/labs/{id}/files/content")
	LabFileResponse getLabFile(@PathVariable long id, @RequestParam String path, Authentication authentication) {
		return labFileService.getLabFile(id, path, userId(authentication));
	}

	@GetMapping("/api/labs/{id}/files/original")
	LabFileResponse getOriginalLabFile(@PathVariable long id, @RequestParam String path,
			Authentication authentication) {
		return labFileService.getOriginalLabFile(id, path, userId(authentication));
	}

	@PutMapping("/api/labs/{id}/files/content")
	LabFileResponse saveLabFile(@PathVariable long id, @RequestParam String path,
			@Valid @RequestBody SaveLabFileRequest body, Authentication authentication) {
		return labFileService.saveLabFile(id, path, body.content(), body.baseSha256(), userId(authentication));
	}

	@PostMapping("/api/labs/{id}/files/revert")
	LabFileResponse revertLabFile(@PathVariable long id, @RequestParam String path, Authentication authentication) {
		return labFileService.revertLabFile(id, path, userId(authentication));
	}

	private static long userId(Authentication authentication) {
		return Long.parseLong(authentication.getName());
	}

}
