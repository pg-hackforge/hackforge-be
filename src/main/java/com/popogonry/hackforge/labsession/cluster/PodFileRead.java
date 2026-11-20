package com.popogonry.hackforge.labsession.cluster;

/** 파일 읽기와 되돌리기 결과. {@code OK}일 때만 내용과 해시가 있다 */
public record PodFileRead(Status status, byte[] content, String sha256) {

	public enum Status {

		OK,

		/** 파일이 없음(되돌리기에서는 원본에 없음) */
		NOT_FOUND,

		/** 읽기 상한을 넘음 */
		TOO_LARGE

	}

	public static PodFileRead of(Status status) {
		return new PodFileRead(status, null, null);
	}

}
