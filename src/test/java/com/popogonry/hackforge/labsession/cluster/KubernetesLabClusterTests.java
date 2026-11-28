package com.popogonry.hackforge.labsession.cluster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

import com.popogonry.hackforge.labsession.domain.LabPrepStep;

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
				Duration.ofMinutes(30), Duration.ofMinutes(5), 0, 5173, Duration.ofSeconds(30), Duration.ofMinutes(1)));
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
	void createsLabThatBecomesReadyAndDeletesIt() throws Exception {
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

		// ADR 021. 백엔드는 이 namespace의 RoleBinding(lab-log-reader)으로만 Pod 로그를 읽을 수 있다.
		// 백엔드에는 RoleBinding 조회 권한이 없으므로 로그를 읽은 것으로 RoleBinding을 확인한다. 시작 스크립트의 단계 기록이 모두 있다
		assertThat(LabPrepStep.fromStartLog(labCluster.readPodLog(SESSION))).isEqualTo(LabPrepStep.STARTING);

		// ADR 022. RoleBinding lab-connect로 port-forward해서 Pod 안 공격 대상 웹(8080)이 백엔드 안 포트로 열린다. 같은 포트를 재사용한다
		int port = labCluster.targetPort(SESSION);
		assertThat(labCluster.targetPort(SESSION)).isEqualTo(port);
		HttpClient http = HttpClient.newHttpClient();
		for (int i = 0; i < 3; i++) {
			HttpResponse<String> page = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/")).build(),
					HttpResponse.BodyHandlers.ofString());
			assertThat(page.statusCode()).isEqualTo(200);
		}
		labCluster.closeTarget(SESSION);

		// ADR 022. exec(TTY)로 /app의 sh에 붙는다. 15초 동안 입출력이 없어도 연결이 끊기지 않는다
		try (LabShell shell = labCluster.openShell(SESSION)) {
			shell.resize(120, 30);
			assertThat(runInShell(shell, "echo \"$(pwd) $(stty size) hf-$((40+2))\"")).contains("/app 30 120 hf-42");
			Thread.sleep(15_000);
			assertThat(runInShell(shell, "tty")).contains("/dev/pts/");
		}

		// ADR 014, ADR 023. 검증 스크립트는 /app에서 sh로 돌고 exit code와 stdout을 준다. 제한 시간을 넘기면 끝낸다
		ScriptResult before = labCluster.runScript(SESSION, "pwd\ntest -f /tmp/hf-attacked", Duration.ofSeconds(60));
		assertThat(before.exitCode()).isEqualTo(1);
		assertThat(before.output()).contains("/app");
		assertThat(labCluster.runScript(SESSION, "touch /tmp/hf-attacked", Duration.ofSeconds(60)).exitCode()).isZero();
		ScriptResult after = labCluster.runScript(SESSION, "test -f /tmp/hf-attacked && echo attacked", Duration.ofSeconds(60));
		assertThat(after.exitCode()).isZero();
		assertThat(after.output()).contains("attacked");
		assertThat(after.timedOut()).isFalse();
		Instant start = Instant.now();
		ScriptResult slow = labCluster.runScript(SESSION, "sleep 30", Duration.ofSeconds(2));
		assertThat(slow.timedOut()).isTrue();
		assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(15));
		// sh는 stdin이 닫혀야 끝난다. 스크립트 안에서 stdin을 읽는 명령도 EOF를 받는다(exec 프로토콜 v5)
		ScriptResult eof = labCluster.runScript(SESSION, "cat\necho eof", Duration.ofSeconds(10));
		assertThat(eof.timedOut()).isFalse();
		assertThat(eof.output()).contains("eof");
		// hackforge-be #60. 빨리 끝나는 exec를 이어서 해도 연결 오류가 나지 않는다. 고치기 전에는 약 90번에 1번 났다
		for (int i = 0; i < 200; i++) {
			assertThat(labCluster.runScript(SESSION, "true", Duration.ofSeconds(10)).exitCode()).as("exec " + i).isZero();
		}
		assertThatThrownBy(() -> http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/")).build(),
				HttpResponse.BodyHandlers.ofString())).isInstanceOf(java.io.IOException.class);

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

	/** 명령을 입력하고 끝 표시가 나올 때까지 출력을 읽는다 */
	private static String runInShell(LabShell shell, String command) throws IOException {
		String marker = "END" + System.nanoTime();
		shell.input().write((command + "; echo " + marker + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
		shell.input().flush();
		StringBuilder out = new StringBuilder();
		byte[] buffer = new byte[4096];
		Instant deadline = Instant.now().plusSeconds(20);
		// 입력 자체도 TTY가 다시 보여 주므로 표시가 두 번 나올 때까지 읽는다
		while (out.indexOf(marker) == out.lastIndexOf(marker) && Instant.now().isBefore(deadline)) {
			int n = shell.output().read(buffer);
			if (n < 0) {
				break;
			}
			out.append(new String(buffer, 0, n, java.nio.charset.StandardCharsets.UTF_8));
		}
		return out.toString();
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
