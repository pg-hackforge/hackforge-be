package com.popogonry.hackforge.labsession.cluster;

public enum LabPodStatus {

	/** Pod가 없음 */
	ABSENT,

	/** 삭제 중. 같은 이름으로 새로 만들 수 없음 */
	DELETING,

	/** 스케줄링, image 준비, 서비스 시작 중 */
	STARTING,

	/** readinessProbe({@code /hackforge/ready.sh}) 통과 */
	READY,

	/** 주 프로세스 종료, eviction, image pull 실패. 다시 시작되지 않음({@code restartPolicy: Never}) */
	FAILED

}
