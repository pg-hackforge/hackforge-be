package com.popogonry.hackforge.labsession.service;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.popogonry.hackforge.labsession.cluster.LabCluster;
import com.popogonry.hackforge.labsession.cluster.PodFileRead;
import com.popogonry.hackforge.labsession.cluster.PodFileWrite;
import com.popogonry.hackforge.labsession.domain.LabSession;
import com.popogonry.hackforge.labsession.domain.LabStage;
import com.popogonry.hackforge.labsession.domain.LabStatus;
import com.popogonry.hackforge.labsession.dto.LabFileEntry;
import com.popogonry.hackforge.labsession.dto.LabFileResponse;
import com.popogonry.hackforge.web.ApiException;
import com.popogonry.hackforge.web.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 웹 IDE의 파일 API(ADR 024). Pod 안 {@code /app}을 exec로 읽고 쓴다. 방어 단계에서만 연다.
 * 수정 불가 파일은 IDE에서 실수로 고치지 않게 하는 안내다. 학습자는 터미널로 어떤 파일이든 고칠 수 있다
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LabFileService {

	/** IDE에서 열고 저장할 수 있는 최대 크기 */
	static final int MAX_BYTES = 1024 * 1024;

	/** 문제가 수정 불가 파일을 정하지 않았을 때 */
	static final String DEFAULT_READONLY = "compose.yaml Dockerfile Dockerfile.base **/Dockerfile **/Dockerfile.base **.sql";

	/** 제어 문자(줄바꿈, 탭 등). 파일 목록을 한 줄에 하나씩 받아서 이름에 넣을 수 없다 */
	private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");

	private final LabSessionService labSessionService;

	private final LabCluster labCluster;

	private final ActivityTracker activityTracker;

	public List<LabFileEntry> listLabFiles(long id, long userId) {
		LabSession lab = openable(id, userId);
		List<Pattern> readOnly = readOnlyPatterns(lab);
		return labCluster.listFiles(id)
			.stream()
			.map(f -> new LabFileEntry(f.path(), f.size(), matches(readOnly, f.path()),
					f.size() <= MAX_BYTES && !f.binary(), f.modified()))
			.toList();
	}

	public LabFileResponse getLabFile(long id, String path, long userId) {
		LabSession lab = openable(id, userId);
		requireValidPath(path);
		return editable(path, labCluster.readFile(id, path, MAX_BYTES), lab);
	}

	/** 원본({@code /hackforge/original})의 파일. 화면이 지금 코드와 비교한다(ADR 029). 활동으로 세지 않는다 */
	public LabFileResponse getOriginalLabFile(long id, String path, long userId) {
		LabSession lab = openable(id, userId);
		requireValidPath(path);
		return editable(path, labCluster.readOriginalFile(id, path, MAX_BYTES), lab);
	}

	/** 읽은 파일이 없으면 404, 1MB 초과나 바이너리면 409 */
	private LabFileResponse editable(String path, PodFileRead read, LabSession lab) {
		return switch (read.status()) {
			case NOT_FOUND -> throw new ApiException(ErrorCode.FILE_NOT_FOUND);
			case TOO_LARGE -> throw new ApiException(ErrorCode.FILE_NOT_EDITABLE);
			case OK -> {
				if (isBinary(read.content())) {
					throw new ApiException(ErrorCode.FILE_NOT_EDITABLE);
				}
				yield response(path, read.content(), read.sha256(), lab);
			}
		};
	}

	/** {@code baseSha256}이 지금 파일과 다르면 409. null이면 확인하지 않고 덮어쓴다 */
	public LabFileResponse saveLabFile(long id, String path, String content, String baseSha256, long userId) {
		LabSession lab = openable(id, userId);
		requireWritable(lab, path);
		byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
		if (bytes.length > MAX_BYTES) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED);
		}
		PodFileWrite write = labCluster.writeFile(id, path, bytes, baseSha256);
		if (write.changed()) {
			throw new ApiException(ErrorCode.FILE_CHANGED);
		}
		activityTracker.record(id);
		return response(path, bytes, write.sha256(), lab);
	}

	public LabFileResponse revertLabFile(long id, String path, long userId) {
		LabSession lab = openable(id, userId);
		requireWritable(lab, path);
		PodFileRead read = labCluster.revertFile(id, path);
		if (read.status() != PodFileRead.Status.OK) {
			throw new ApiException(ErrorCode.FILE_NOT_FOUND);
		}
		activityTracker.record(id);
		return response(path, read.content(), read.sha256(), lab);
	}

	private LabSession openable(long id, long userId) {
		LabSession lab = labSessionService.getLab(id, userId);
		if (lab.getStatus() != LabStatus.READY) {
			throw new ApiException(ErrorCode.LAB_NOT_READY);
		}
		if (lab.getStage() != LabStage.DEFENSE) {
			throw new ApiException(ErrorCode.WRONG_LAB_STAGE);
		}
		return lab;
	}

	private void requireWritable(LabSession lab, String path) {
		requireValidPath(path);
		if (matches(readOnlyPatterns(lab), path)) {
			throw new ApiException(ErrorCode.FILE_READ_ONLY);
		}
	}

	private LabFileResponse response(String path, byte[] content, String sha256, LabSession lab) {
		return new LabFileResponse(path, new String(content, StandardCharsets.UTF_8), sha256,
				matches(readOnlyPatterns(lab), path));
	}

	/**
	 * {@code /app} 기준 상대 경로만. {@code ..}, 절대 경로, 빈 조각, 제어 문자는 400. 경로는 스크립트에 글자 그대로
	 * 넣지 않으므로(base64) 한글, 빈칸, 셸 특수 문자는 받는다
	 */
	static void requireValidPath(String path) {
		if (path == null || path.isEmpty() || path.length() > 500 || path.startsWith("/") || CONTROL.matcher(path).find()
				|| Arrays.stream(path.split("/", -1)).anyMatch(s -> s.isEmpty() || s.equals(".") || s.equals(".."))) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED);
		}
	}

	private static List<Pattern> readOnlyPatterns(LabSession lab) {
		String paths = lab.getChallenge().getReadonlyPaths();
		String globs = paths == null || paths.isBlank() ? DEFAULT_READONLY : paths;
		return Arrays.stream(globs.strip().split("\\s+")).map(LabFileService::globToRegex).toList();
	}

	private static boolean matches(List<Pattern> patterns, String path) {
		return patterns.stream().anyMatch(p -> p.matcher(path).matches());
	}

	/** {@code **}는 어느 깊이든, {@code *}와 {@code ?}는 한 경로 조각 안에서 */
	static Pattern globToRegex(String glob) {
		StringBuilder regex = new StringBuilder();
		for (int i = 0; i < glob.length(); i++) {
			char c = glob.charAt(i);
			if (c == '*' && i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
				regex.append(".*");
				i++;
			}
			else if (c == '*') {
				regex.append("[^/]*");
			}
			else if (c == '?') {
				regex.append("[^/]");
			}
			else {
				regex.append(Pattern.quote(String.valueOf(c)));
			}
		}
		return Pattern.compile(regex.toString());
	}

	private static boolean isBinary(byte[] content) {
		for (byte b : content) {
			if (b == 0) {
				return true;
			}
		}
		return false;
	}

}
