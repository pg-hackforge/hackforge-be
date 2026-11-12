package com.popogonry.hackforge.labsession.cluster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.NetworkingV1Api;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1SecurityContext;
import io.kubernetes.client.util.Config;

/**
 * 개발 VM 클러스터에 실제로 만들고 지운다. {@code ./gradlew clusterTest}로만 실행하고 {@code build}에서는 빠진다.
 * 환경 변수 {@code HACKFORGE_LAB_KUBECONFIG}에 백엔드 kubeconfig(hackforge-docs {@code infra/cluster-setup.md} 8.3) 경로가 필요하고,
 * 노드에 테스트 문제 이미지(hackforge-docs {@code infra/lab-test-challenge})가 import돼 있어야 한다.
 */
@Tag("cluster")
class KubernetesLabClusterTests {

	private static final String TEST_IMAGE = "hackforge/lab-test-challenge:0.1.0";

	// 개발 DB 세션 id와 겹치지 않게 큰 값
	private static final long SESSION = 900_000_001L;

	private static final long BAD_IMAGE_SESSION = 900_000_002L;

	private KubernetesLabCluster labCluster;

	private CoreV1Api core;

	@BeforeEach
	void setUp() throws IOException {
		String kubeconfig = System.getenv("HACKFORGE_LAB_KUBECONFIG");
		assertThat(kubeconfig).as("환경 변수 HACKFORGE_LAB_KUBECONFIG").isNotBlank();
		labCluster = new KubernetesLabCluster(new LabProperties(kubeconfig, "500m", "512Mi", "2Gi", 2, Duration.ofMinutes(5),
				Duration.ofMinutes(30), Duration.ofMinutes(5)));
		core = new CoreV1Api(Config.fromConfig(kubeconfig));
	}

	@AfterEach
	void tearDown() {
		labCluster.deleteNamespace(SESSION);
		labCluster.deleteNamespace(BAD_IMAGE_SESSION);
		waitUntil(Duration.ofMinutes(2),
				() -> !labCluster.namespaceExists(SESSION) && !labCluster.namespaceExists(BAD_IMAGE_SESSION));
	}

	@Test
	void createsLabThatBecomesReadyAndDeletesIt() throws ApiException {
		assertThat(labCluster.namespaceExists(SESSION)).isFalse();
		assertThat(labCluster.getPodStatus(SESSION)).isEqualTo(LabPodStatus.ABSENT);

		labCluster.createNamespace(SESSION);
		labCluster.createPod(SESSION, TEST_IMAGE);

		String namespace = "lab-" + SESSION;
		assertThat(core.readNamespace(namespace).execute().getMetadata().getLabels())
			.containsEntry("hackforge/lab", "true")
			.containsEntry("pod-security.kubernetes.io/enforce", "baseline");
		assertThat(new NetworkingV1Api(core.getApiClient()).readNamespacedNetworkPolicy("deny-all", namespace)
			.execute()
			.getSpec()
			.getPolicyTypes()).containsExactlyInAnyOrder("Ingress", "Egress");
		assertThat(core.readNamespacedResourceQuota("lab", namespace).execute().getSpec().getHard())
			.containsKeys("pods", "limits.cpu", "limits.memory", "requests.ephemeral-storage");
		V1Pod pod = core.readNamespacedPod("lab", namespace).execute();
		assertThat(pod.getSpec().getAutomountServiceAccountToken()).isFalse();
		assertThat(pod.getSpec().getHostUsers()).isFalse();
		assertThat(pod.getSpec().getRuntimeClassName()).isEqualTo("sysbox-runc");
		assertThat(pod.getSpec().getDnsPolicy()).isEqualTo("None");
		assertThat(pod.getSpec().getDnsConfig().getNameservers()).containsExactly("127.0.0.1");
		assertThat(pod.getSpec().getDnsConfig().getSearches()).isNullOrEmpty();

		waitUntil(Duration.ofMinutes(2), () -> labCluster.getPodStatus(SESSION) == LabPodStatus.READY);

		labCluster.deletePod(SESSION);
		assertThat(labCluster.getPodStatus(SESSION)).isIn(LabPodStatus.DELETING, LabPodStatus.ABSENT);
		labCluster.deleteNamespace(SESSION);
		waitUntil(Duration.ofMinutes(2), () -> !labCluster.namespaceExists(SESSION));
	}

	@Test
	void listsLabNamespaceAsSessionId() {
		assertThat(labCluster.listSessionIds()).doesNotContain(SESSION);

		labCluster.createNamespace(SESSION);

		assertThat(labCluster.listSessionIds()).contains(SESSION);
	}

	@Test
	void creatingTwiceKeepsOnePod() throws ApiException {
		labCluster.createNamespace(SESSION);
		labCluster.createNamespace(SESSION);
		labCluster.createPod(SESSION, TEST_IMAGE);
		labCluster.createPod(SESSION, TEST_IMAGE);

		assertThat(core.listNamespacedPod("lab-" + SESSION).execute().getItems()).hasSize(1);
	}

	@Test
	void deletingMissingObjectsSucceeds() {
		labCluster.deletePod(SESSION);
		labCluster.deleteNamespace(SESSION);
	}

	@Test
	void imagePullFailureIsFailed() {
		labCluster.createNamespace(BAD_IMAGE_SESSION);
		labCluster.createPod(BAD_IMAGE_SESSION, "hackforge/does-not-exist:0.0.0");

		waitUntil(Duration.ofMinutes(2), () -> labCluster.getPodStatus(BAD_IMAGE_SESSION) == LabPodStatus.FAILED);
	}

	@Test
	void privilegedPodIsRejectedByPodSecurity() {
		labCluster.createNamespace(SESSION);

		assertThatThrownBy(() -> core.createNamespacedPod("lab-" + SESSION, privilegedPod()).dryRun("All").execute())
			.isInstanceOfSatisfying(ApiException.class, ex -> {
				assertThat(ex.getCode()).isEqualTo(403);
				assertThat(ex.getResponseBody()).contains("PodSecurity");
			});
	}

	/** ValidatingAdmissionPolicy 거절은 validation에 reason이 없으면 422 Invalid로 온다 */
	@Test
	void kubeSystemIsRejectedByAdmissionPolicy() throws ApiException {
		assertThatThrownBy(() -> core.createNamespacedPod("kube-system", privilegedPod()).dryRun("All").execute())
			.isInstanceOfSatisfying(ApiException.class, ex -> {
				assertThat(ex.getResponseBody()).contains("hackforge-backend-lab-only");
				assertThat(ex.getCode()).isEqualTo(422);
			});
		String coreDns = core.listNamespacedPod("kube-system")
			.labelSelector("k8s-app=kube-dns")
			.execute()
			.getItems()
			.getFirst()
			.getMetadata()
			.getName();
		assertThatThrownBy(() -> core.deleteNamespacedPod(coreDns, "kube-system").dryRun("All").execute())
			.isInstanceOfSatisfying(ApiException.class, ex -> {
				assertThat(ex.getResponseBody()).contains("hackforge-backend-lab-only");
				assertThat(ex.getCode()).isEqualTo(422);
			});
	}

	private static V1Pod privilegedPod() {
		return new V1Pod().metadata(new V1ObjectMeta().name("priv"))
			.spec(KubernetesLabCluster.podSpec().containers(List.of(new V1Container().name("c")
				.image("busybox:1.37")
				.securityContext(new V1SecurityContext().privileged(true)))));
	}

	private static void waitUntil(Duration timeout, BooleanSupplier condition) {
		Instant deadline = Instant.now().plus(timeout);
		while (!condition.getAsBoolean()) {
			assertThat(Instant.now()).as("%s 안에 조건을 만족하지 않음", timeout).isBefore(deadline);
			try {
				Thread.sleep(2000);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(ex);
			}
		}
	}

}
