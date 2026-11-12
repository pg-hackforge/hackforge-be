package com.popogonry.hackforge.labsession.cluster;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.kubernetes.client.openapi.models.V1ContainerState;
import io.kubernetes.client.openapi.models.V1ContainerStateWaiting;
import io.kubernetes.client.openapi.models.V1ContainerStatus;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodCondition;
import io.kubernetes.client.openapi.models.V1PodStatus;

/** API server가 돌려준 Pod를 {@link LabPodStatus}로 바꾸는 규칙. 실제 클러스터 확인은 {@link KubernetesLabClusterTests} */
class LabPodStatusTests {

	@Test
	void podWithoutStatusIsStarting() {
		assertThat(KubernetesLabCluster.statusOf(pod(null))).isEqualTo(LabPodStatus.STARTING);
	}

	@Test
	void runningButNotReadyIsStarting() {
		assertThat(KubernetesLabCluster.statusOf(pod(new V1PodStatus().phase("Running")
			.addConditionsItem(new V1PodCondition().type("Ready").status("False")))))
			.isEqualTo(LabPodStatus.STARTING);
	}

	@Test
	void readyConditionIsReady() {
		assertThat(KubernetesLabCluster.statusOf(pod(new V1PodStatus().phase("Running")
			.addConditionsItem(new V1PodCondition().type("Ready").status("True")))))
			.isEqualTo(LabPodStatus.READY);
	}

	@Test
	void failedOrSucceededPhaseIsFailed() {
		assertThat(KubernetesLabCluster.statusOf(pod(new V1PodStatus().phase("Failed"))))
			.isEqualTo(LabPodStatus.FAILED);
		assertThat(KubernetesLabCluster.statusOf(pod(new V1PodStatus().phase("Succeeded"))))
			.isEqualTo(LabPodStatus.FAILED);
	}

	@Test
	void imagePullFailureIsFailedWhilePending() {
		for (String reason : List.of("ErrImagePull", "ImagePullBackOff")) {
			assertThat(KubernetesLabCluster.statusOf(pod(new V1PodStatus().phase("Pending")
				.addContainerStatusesItem(waiting(reason))))).isEqualTo(LabPodStatus.FAILED);
		}
	}

	@Test
	void creatingContainerIsStarting() {
		assertThat(KubernetesLabCluster.statusOf(pod(new V1PodStatus().phase("Pending")
			.addContainerStatusesItem(waiting("ContainerCreating"))))).isEqualTo(LabPodStatus.STARTING);
	}

	@Test
	void deletionTimestampWinsOverReady() {
		V1Pod pod = pod(new V1PodStatus().phase("Running")
			.addConditionsItem(new V1PodCondition().type("Ready").status("True")));
		pod.getMetadata().deletionTimestamp(OffsetDateTime.now());
		assertThat(KubernetesLabCluster.statusOf(pod)).isEqualTo(LabPodStatus.DELETING);
	}

	private static V1Pod pod(V1PodStatus status) {
		return new V1Pod().metadata(new V1ObjectMeta().name("lab")).status(status);
	}

	private static V1ContainerStatus waiting(String reason) {
		return new V1ContainerStatus().name("lab")
			.state(new V1ContainerState().waiting(new V1ContainerStateWaiting().reason(reason)));
	}

}
