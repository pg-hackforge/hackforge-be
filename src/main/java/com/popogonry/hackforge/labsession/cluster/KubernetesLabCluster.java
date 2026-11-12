package com.popogonry.hackforge.labsession.cluster;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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
import io.kubernetes.client.openapi.models.V1PodDNSConfig;
import io.kubernetes.client.openapi.models.V1PodSpec;
import io.kubernetes.client.openapi.models.V1Probe;
import io.kubernetes.client.openapi.models.V1ResourceQuota;
import io.kubernetes.client.openapi.models.V1ResourceQuotaSpec;
import io.kubernetes.client.openapi.models.V1ResourceRequirements;
import io.kubernetes.client.openapi.models.V1Volume;
import io.kubernetes.client.openapi.models.V1VolumeMount;
import io.kubernetes.client.util.Config;

/**
 * {@link LabCluster}의 client-java 구현. 만드는 오브젝트는 ADR 013의 manifest와 같다.
 *
 * <p>client-java 사용법 요약
 * <ul>
 * <li>{@code V1Namespace}, {@code V1Pod} 같은 {@code V1*} 클래스는 YAML manifest를 Java 객체로 옮긴 모델이다. YAML의
 * {@code metadata.name: lab}은 {@code new V1Pod().metadata(new V1ObjectMeta().name("lab"))}처럼 setter를 이어 붙여(fluent
 * style) 만든다. 클래스 이름의 {@code V1}은 Kubernetes API 버전({@code apiVersion: v1})이다.
 * <li>{@code CoreV1Api}, {@code NetworkingV1Api}는 API server에 보내는 요청 묶음이다. API group마다 클래스가 나뉜다. namespace,
 * Pod, ResourceQuota는 core group({@code apiVersion: v1}), NetworkPolicy는 {@code networking.k8s.io/v1}이다.
 * <li>{@code core().createNamespace(body)}는 요청을 준비만 하고, 끝의 {@code .execute()}가 실제 HTTP 요청을 보낸다. kubectl로
 * 치면 {@code createX}는 {@code kubectl create}, {@code readX}는 {@code kubectl get}, {@code deleteX}는
 * {@code kubectl delete}다.
 * <li>API server가 오류로 응답하면 {@code ApiException}이 나고 {@code getCode()}가 HTTP 상태 코드다. 409는 이미 있음,
 * 404는 없음, 403은 권한·정책 거절이다.
 * </ul>
 */
@Component
// LabProperties(hackforge.lab.* 설정)를 bean으로 등록해 생성자에 주입되게 한다
@EnableConfigurationProperties(LabProperties.class)
class KubernetesLabCluster implements LabCluster {

	/** namespace마다 Pod는 하나라 이름을 고정한다(ADR 013). 중복 생성이 이름으로 막힌다 */
	static final String POD_NAME = "lab";

	/**
	 * container가 이 이유로 waiting이면 image를 못 받은 것이다. kubelet이 계속 다시 시도하지만 대부분 저절로 풀리지 않아서
	 * 실패로 본다. 오타난 이미지 이름, 노드에 import되지 않은 이미지 등
	 */
	private static final Set<String> IMAGE_FAILURES = Set.of("ErrImagePull", "ImagePullBackOff",
			"InvalidImageName", "ErrImageNeverPull");

	// Quantity는 Kubernetes 자원 값("500m", "512Mi", "2Gi")을 나타내는 client-java 타입이다.
	// 설정 문자열을 생성자에서 미리 바꿔 두므로, 형식이 틀리면 요청 때가 아니라 애플리케이션 시작 때 실패한다
	private final Quantity cpu;

	private final Quantity memory;

	private final Quantity disk;

	// API server 접속 정보(주소, 인증서, token)를 가진 client.
	// SingletonSupplier는 처음 get()을 부를 때 한 번만 만들고 그다음부터는 같은 객체를 돌려준다.
	// 접속을 생성자가 아니라 첫 호출 때 하므로, 클러스터가 없는 테스트와 CI에서도 애플리케이션이 뜬다
	private final Supplier<ApiClient> client;

	KubernetesLabCluster(LabProperties labProperties) {
		this.cpu = Quantity.fromString(labProperties.cpu());
		this.memory = Quantity.fromString(labProperties.memory());
		this.disk = Quantity.fromString(labProperties.disk());
		this.client = SingletonSupplier.of(() -> connect(labProperties.kubeconfig()));
	}

	/** 세션 id로 namespace 이름을 만든다. 백엔드는 VAP 때문에 {@code lab-}로 시작하는 namespace만 다룰 수 있다(ADR 013) */
	static String namespace(long sessionId) {
		return "lab-" + sessionId;
	}

	@Override
	public void createNamespace(long sessionId) {
		String namespace = namespace(sessionId);
		// 아래 세 요청은 각각 ignoreConflict로 감싼다. 이미 있으면(409) 성공으로 보고 다음 단계로 간다.
		// 중간에 실패해서 reconciler가 다시 불러도 있는 것은 건너뛰고 없는 것만 만든다(멱등)

		// apiVersion: v1 / kind: Namespace
		// metadata: { name: lab-<id>, labels: { hackforge/lab: "true", pod-security.kubernetes.io/enforce: baseline } }
		// hackforge/lab label은 Calico GlobalNetworkPolicy와 VAP가 실습 namespace를 알아보는 표시이고,
		// pod-security label은 privileged Pod 등을 거절하게 한다(Pod Security Admission baseline)
		ignoreConflict(() -> core().createNamespace(new V1Namespace()
			.metadata(new V1ObjectMeta().name(namespace)
				.labels(Map.of("hackforge/lab", "true", "pod-security.kubernetes.io/enforce", "baseline"))))
			.execute());

		// apiVersion: networking.k8s.io/v1 / kind: NetworkPolicy
		// metadata: { name: deny-all }
		// spec: { podSelector: {}, policyTypes: [Ingress, Egress] }
		// podSelector가 비어 있으면 namespace의 모든 Pod가 대상이다. 허용 규칙이 없으므로 들어오고 나가는 통신이 모두 막힌다
		ignoreConflict(() -> networking().createNamespacedNetworkPolicy(namespace, new V1NetworkPolicy()
			.metadata(new V1ObjectMeta().name("deny-all"))
			.spec(new V1NetworkPolicySpec().podSelector(new V1LabelSelector()).policyTypes(List.of("Ingress", "Egress"))))
			.execute());

		// apiVersion: v1 / kind: ResourceQuota
		// metadata: { name: lab }
		// spec.hard: namespace 전체가 넘을 수 없는 합계. Pod 1개, CPU·메모리·디스크는 세션 한 개 분량.
		// 백엔드 버그로 Pod가 더 만들어지거나 자원 설정이 빠진 Pod가 들어오는 것을 막는다
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
		// requests(스케줄링할 때 노드에 확보하는 양)와 limits(넘으면 제한되는 상한)에 같은 값을 쓴다(ADR 013).
		// ephemeral-storage는 container가 노드 디스크에 쓰는 양이다
		Map<String, Quantity> resources = Map.of("cpu", cpu, "memory", memory, "ephemeral-storage", disk);
		V1Pod pod = new V1Pod().metadata(new V1ObjectMeta().name(POD_NAME))
			.spec(podSpec()
				// Sysbox runtime으로 실행해서 privileged 없이 Pod 안에서 dockerd를 돌린다(ADR 009)
				.runtimeClassName("sysbox-runc")
				// user namespace 사용. Pod 안의 root가 노드에서는 일반 사용자다
				.hostUsers(false)
				// API token을 Pod에 넣지 않는다. 학습자가 root 셸을 가지므로 token이 있으면 API server를 호출할 수 있다
				.automountServiceAccountToken(false)
				// 클러스터의 Service 주소를 환경 변수로 넣지 않는다
				.enableServiceLinks(false)
				// 기본값(ClusterFirst)이면 Pod의 search 도메인과 ndots:5가 inner container로 넘어가서, compose 서비스 이름(db)을
				// 클러스터 DNS에 먼저 묻다가 네트워크 차단에 걸려 timeout이 난다. 클러스터 DNS는 어차피 막혀 있으므로 search 없이
				// loopback만 둔다. 서비스 이름은 inner docker의 내장 DNS가 바로 응답한다(ADR 013)
				.dnsPolicy("None")
				.dnsConfig(new V1PodDNSConfig().nameservers(List.of("127.0.0.1")))
				// 주 프로세스가 죽으면 다시 띄우지 않고 Failed로 둔다. 재시도와 종료는 reconciler가 판단한다
				.restartPolicy("Never")
				.addContainersItem(new V1Container().name("lab")
					.image(image)
					// 노드에 이미지가 있으면 받지 않는다. 실습 이미지는 registry 없이 노드에 import해 둔다(ADR 012)
					.imagePullPolicy("IfNotPresent")
					.resources(new V1ResourceRequirements().requests(resources).limits(resources))
					// kubelet이 3초마다 ready.sh를 실행하고, 성공하면 Pod의 Ready condition이 True가 된다.
					// ready.sh는 compose 서비스가 모두 healthy인지 본다
					.readinessProbe(new V1Probe().exec(new V1ExecAction().command(List.of("/hackforge/ready.sh")))
						.periodSeconds(3))
					// inner docker의 이미지와 container 데이터가 쌓이는 폴더를 아래 emptyDir에 연결한다
					.addVolumeMountsItem(new V1VolumeMount().name("docker").mountPath("/var/lib/docker")))
				// emptyDir는 Pod가 살아 있는 동안만 있는 임시 폴더다. kubelet이 사용량을 재고 sizeLimit을 넘으면 Pod를
				// eviction한다. 학습자가 디스크를 채워도 노드 전체가 멈추지 않게 하는 장치다(ADR 013)
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
			// kubectl get pod lab -n lab-<id>
			return statusOf(core().readNamespacedPod(POD_NAME, namespace(sessionId)).execute());
		}
		catch (ApiException ex) {
			// Pod가 없으면 404. namespace가 없어도 404다
			if (ex.getCode() == 404) {
				return LabPodStatus.ABSENT;
			}
			throw new LabClusterException(ex);
		}
	}

	/**
	 * API server가 돌려준 Pod를 우리가 쓰는 5가지 상태로 바꾼다. 위에서부터 먼저 맞는 조건이 이긴다.
	 *
	 * <ol>
	 * <li>{@code metadata.deletionTimestamp}가 있으면 삭제가 시작된 Pod다. 다른 상태와 관계없이 {@code DELETING}
	 * <li>{@code status}가 아직 없으면 막 만들어져 스케줄링 전이다
	 * <li>{@code status.phase}는 Pod 전체 단계(Pending, Running, Succeeded, Failed)다. 끝난 단계면 {@code FAILED}
	 * <li>{@code status.containerStatuses[].state.waiting.reason}으로 image pull 실패를 찾는다. 이때 phase는 Pending이라 3번에
	 * 걸리지 않는다
	 * <li>{@code status.conditions}의 {@code Ready}가 True면 readinessProbe를 통과한 것이다
	 * </ol>
	 */
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
		// container 상태 목록은 아직 없을 수 있어서(null) 빈 목록으로 바꿔 둔다
		List<V1ContainerStatus> containers = status.getContainerStatuses() == null ? List.of()
				: status.getContainerStatuses();
		for (V1ContainerStatus container : containers) {
			// container 상태는 waiting, running, terminated 중 하나만 값이 있다
			var waiting = container.getState() == null ? null : container.getState().getWaiting();
			if (waiting != null && IMAGE_FAILURES.contains(waiting.getReason())) {
				return LabPodStatus.FAILED;
			}
		}
		// conditions 중 type이 Ready이고 status가 "True"인 것이 하나라도 있는지 본다
		boolean ready = status.getConditions() != null && status.getConditions()
			.stream()
			.anyMatch(c -> "Ready".equals(c.getType()) && "True".equals(c.getStatus()));
		return ready ? LabPodStatus.READY : LabPodStatus.STARTING;
	}

	@Override
	public void deletePod(long sessionId) {
		// 없으면(404) 이미 지워진 것으로 본다
		ignoreNotFound(() -> core().deleteNamespacedPod(POD_NAME, namespace(sessionId)).execute());
	}

	@Override
	public void deleteNamespace(long sessionId) {
		// namespace를 지우면 안의 Pod, NetworkPolicy, ResourceQuota도 Kubernetes가 함께 지운다.
		// 요청은 바로 응답하고 실제 삭제는 수십 초 걸린다. 끝났는지는 namespaceExists로 본다
		ignoreNotFound(() -> core().deleteNamespace(namespace(sessionId)).execute());
	}

	@Override
	public boolean namespaceExists(long sessionId) {
		try {
			// 삭제 중(Terminating)인 namespace도 조회되므로 true다
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

	@Override
	public Set<Long> listSessionIds() {
		List<V1Namespace> namespaces;
		try {
			// kubectl get namespaces -l hackforge/lab=true
			namespaces = core().listNamespace().labelSelector("hackforge/lab=true").execute().getItems();
		}
		catch (ApiException ex) {
			throw new LabClusterException(ex);
		}
		Set<Long> ids = new TreeSet<>();
		for (V1Namespace ns : namespaces) {
			String name = ns.getMetadata().getName();
			// 백엔드가 만든 이름(lab-<세션 id>)만 본다
			if (name.matches("lab-\\d+")) {
				ids.add(Long.parseLong(name.substring("lab-".length())));
			}
		}
		return ids;
	}

	// CoreV1Api, NetworkingV1Api는 ApiClient를 감싸는 가벼운 객체라 호출할 때마다 만들어도 된다
	private CoreV1Api core() {
		return new CoreV1Api(client.get());
	}

	private NetworkingV1Api networking() {
		return new NetworkingV1Api(client.get());
	}

	/**
	 * 개발: kubeconfig 파일(백엔드 service account token으로 만든 것)을 읽는다.
	 * 운영: 설정이 비어 있으면 Pod 안에 자동으로 들어오는 service account token과 인증서를 읽는다(in-cluster 설정).
	 */
	private static ApiClient connect(String kubeconfig) {
		try {
			return StringUtils.hasText(kubeconfig) ? Config.fromConfig(kubeconfig) : Config.fromCluster();
		}
		catch (IOException ex) {
			// 파일이 없거나 Pod 밖에서 in-cluster 설정을 읽으려 할 때. checked 예외라 unchecked로 바꿔 던진다
			throw new UncheckedIOException("클러스터 접속 설정을 읽지 못함", ex);
		}
	}

	/** 생성 요청. 이미 있으면(409 Conflict) 성공으로 본다. 그 밖의 오류는 LabClusterException */
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

	/** 삭제 요청. 이미 없으면(404 Not Found) 성공으로 본다. 그 밖의 오류는 LabClusterException */
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

	/**
	 * {@code execute()}는 checked 예외인 {@code ApiException}을 던진다. Java 기본 함수형 인터페이스({@code Runnable} 등)는
	 * checked 예외를 던질 수 없어서 lambda로 넘기려고 이 인터페이스를 따로 둔다.
	 */
	@FunctionalInterface
	private interface ApiCall {

		void run() throws ApiException;

	}

}
