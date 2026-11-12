package com.popogonry.hackforge.labsession.cluster;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.util.function.SingletonSupplier;

import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.NetworkingV1Api;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1ContainerStatus;
import io.kubernetes.client.openapi.models.V1EmptyDirVolumeSource;
import io.kubernetes.client.openapi.models.V1ExecAction;
import io.kubernetes.client.openapi.models.V1LabelSelector;
import io.kubernetes.client.openapi.models.V1Namespace;
import io.kubernetes.client.openapi.models.V1NetworkPolicy;
import io.kubernetes.client.openapi.models.V1NetworkPolicySpec;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodSpec;
import io.kubernetes.client.openapi.models.V1Probe;
import io.kubernetes.client.openapi.models.V1ResourceQuota;
import io.kubernetes.client.openapi.models.V1ResourceQuotaSpec;
import io.kubernetes.client.openapi.models.V1ResourceRequirements;
import io.kubernetes.client.openapi.models.V1Volume;
import io.kubernetes.client.openapi.models.V1VolumeMount;
import io.kubernetes.client.util.Config;

/** {@link LabCluster}의 client-java 구현. 만드는 오브젝트는 ADR 013의 manifest와 같다. */
@Component
@EnableConfigurationProperties(LabProperties.class)
class KubernetesLabCluster implements LabCluster {

	static final String POD_NAME = "lab";

	private static final Set<String> IMAGE_FAILURES = Set.of("ErrImagePull", "ImagePullBackOff",
			"InvalidImageName", "ErrImageNeverPull");

	private final Quantity cpu;

	private final Quantity memory;

	private final Quantity disk;

	// 접속은 처음 호출할 때 한다. 클러스터가 없는 테스트와 CI에서도 애플리케이션이 뜨도록
	private final Supplier<ApiClient> client;

	KubernetesLabCluster(LabProperties labProperties) {
		this.cpu = Quantity.fromString(labProperties.cpu());
		this.memory = Quantity.fromString(labProperties.memory());
		this.disk = Quantity.fromString(labProperties.disk());
		this.client = SingletonSupplier.of(() -> connect(labProperties.kubeconfig()));
	}

	static String namespace(long sessionId) {
		return "lab-" + sessionId;
	}

	@Override
	public void createNamespace(long sessionId) {
		String namespace = namespace(sessionId);
		ignoreConflict(() -> core().createNamespace(new V1Namespace()
			.metadata(new V1ObjectMeta().name(namespace)
				.labels(Map.of("hackforge/lab", "true", "pod-security.kubernetes.io/enforce", "baseline"))))
			.execute());
		ignoreConflict(() -> networking().createNamespacedNetworkPolicy(namespace, new V1NetworkPolicy()
			.metadata(new V1ObjectMeta().name("deny-all"))
			.spec(new V1NetworkPolicySpec().podSelector(new V1LabelSelector()).policyTypes(List.of("Ingress", "Egress"))))
			.execute());
		ignoreConflict(() -> core().createNamespacedResourceQuota(namespace, new V1ResourceQuota()
			.metadata(new V1ObjectMeta().name("lab"))
			.spec(new V1ResourceQuotaSpec().hard(Map.of("pods", new Quantity("1"), "requests.cpu", cpu, "limits.cpu", cpu,
					"requests.memory", memory, "limits.memory", memory, "requests.ephemeral-storage", disk))))
			.execute());
	}

	/**
	 * 이미 있으면(삭제 중 포함) 만들지 않는다. 같은 이름으로 다시 만들면 409보다 ResourceQuota({@code pods: 1}) 초과 403이
	 * 먼저 나서 충돌 응답으로는 구분할 수 없다.
	 */
	@Override
	public void createPod(long sessionId, String image) {
		if (getPodStatus(sessionId) != LabPodStatus.ABSENT) {
			return;
		}
		Map<String, Quantity> resources = Map.of("cpu", cpu, "memory", memory, "ephemeral-storage", disk);
		V1Pod pod = new V1Pod().metadata(new V1ObjectMeta().name(POD_NAME))
			.spec(podSpec().runtimeClassName("sysbox-runc")
				.hostUsers(false)
				.automountServiceAccountToken(false)
				.enableServiceLinks(false)
				.restartPolicy("Never")
				.addContainersItem(new V1Container().name("lab")
					.image(image)
					.imagePullPolicy("IfNotPresent")
					.resources(new V1ResourceRequirements().requests(resources).limits(resources))
					.readinessProbe(new V1Probe().exec(new V1ExecAction().command(List.of("/hackforge/ready.sh")))
						.periodSeconds(3))
					.addVolumeMountsItem(new V1VolumeMount().name("docker").mountPath("/var/lib/docker")))
				.addVolumesItem(new V1Volume().name("docker").emptyDir(new V1EmptyDirVolumeSource().sizeLimit(disk))));
		ignoreConflict(() -> core().createNamespacedPod(namespace(sessionId), pod).execute());
	}

	/**
	 * client-java 모델은 map 필드를 빈 map으로 만들어 그대로 보낸다. {@code overhead: {}}가 가면 RuntimeClass admission이
	 * "Pod Overhead set without corresponding RuntimeClass defined Overhead"로 거절하므로 비워 둔다.
	 */
	static V1PodSpec podSpec() {
		return new V1PodSpec().overhead(null);
	}

	@Override
	public LabPodStatus getPodStatus(long sessionId) {
		try {
			return statusOf(core().readNamespacedPod(POD_NAME, namespace(sessionId)).execute());
		}
		catch (ApiException ex) {
			if (ex.getCode() == 404) {
				return LabPodStatus.ABSENT;
			}
			throw new LabClusterException(ex);
		}
	}

	static LabPodStatus statusOf(V1Pod pod) {
		if (pod.getMetadata().getDeletionTimestamp() != null) {
			return LabPodStatus.DELETING;
		}
		var status = pod.getStatus();
		if (status == null) {
			return LabPodStatus.STARTING;
		}
		// restartPolicy: Never라 Succeeded도 주 프로세스가 끝난 것이다
		if ("Failed".equals(status.getPhase()) || "Succeeded".equals(status.getPhase())) {
			return LabPodStatus.FAILED;
		}
		List<V1ContainerStatus> containers = status.getContainerStatuses() == null ? List.of()
				: status.getContainerStatuses();
		for (V1ContainerStatus container : containers) {
			var waiting = container.getState() == null ? null : container.getState().getWaiting();
			if (waiting != null && IMAGE_FAILURES.contains(waiting.getReason())) {
				return LabPodStatus.FAILED;
			}
		}
		boolean ready = status.getConditions() != null && status.getConditions()
			.stream()
			.anyMatch(c -> "Ready".equals(c.getType()) && "True".equals(c.getStatus()));
		return ready ? LabPodStatus.READY : LabPodStatus.STARTING;
	}

	@Override
	public void deletePod(long sessionId) {
		ignoreNotFound(() -> core().deleteNamespacedPod(POD_NAME, namespace(sessionId)).execute());
	}

	@Override
	public void deleteNamespace(long sessionId) {
		ignoreNotFound(() -> core().deleteNamespace(namespace(sessionId)).execute());
	}

	@Override
	public boolean namespaceExists(long sessionId) {
		try {
			core().readNamespace(namespace(sessionId)).execute();
			return true;
		}
		catch (ApiException ex) {
			if (ex.getCode() == 404) {
				return false;
			}
			throw new LabClusterException(ex);
		}
	}

	private CoreV1Api core() {
		return new CoreV1Api(client.get());
	}

	private NetworkingV1Api networking() {
		return new NetworkingV1Api(client.get());
	}

	private static ApiClient connect(String kubeconfig) {
		try {
			return StringUtils.hasText(kubeconfig) ? Config.fromConfig(kubeconfig) : Config.fromCluster();
		}
		catch (IOException ex) {
			throw new UncheckedIOException("클러스터 접속 설정을 읽지 못함", ex);
		}
	}

	private static void ignoreConflict(ApiCall call) {
		try {
			call.run();
		}
		catch (ApiException ex) {
			if (ex.getCode() != 409) {
				throw new LabClusterException(ex);
			}
		}
	}

	private static void ignoreNotFound(ApiCall call) {
		try {
			call.run();
		}
		catch (ApiException ex) {
			if (ex.getCode() != 404) {
				throw new LabClusterException(ex);
			}
		}
	}

	@FunctionalInterface
	private interface ApiCall {

		void run() throws ApiException;

	}

}
