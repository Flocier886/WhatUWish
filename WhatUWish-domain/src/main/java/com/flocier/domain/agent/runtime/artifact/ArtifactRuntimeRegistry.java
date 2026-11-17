package com.flocier.domain.agent.runtime.artifact;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * 单次 Agent run 内 artifact 的唯一运行期事实源。
 * 数据库 ledger 是持久化历史，不能反过来替代本登记簿的当前调用归属。
 */
public class ArtifactRuntimeRegistry {
    private final List<ArtifactRuntimeBinding> bindings = new ArrayList<>();

    public synchronized ArtifactRuntimeBinding register(ArtifactRuntimeSource source, ArtifactRuntimeDescriptor descriptor) {
        if (source == null || descriptor == null || !descriptor.hasStableLocation()) {
            return null;
        }
        ArtifactRuntimeBinding candidate = ArtifactRuntimeBinding.builder().source(source).descriptor(descriptor).build();
        for (ArtifactRuntimeBinding existing : bindings) {
            if (sameIdentity(existing, candidate)) {
                return existing;
            }
        }
        bindings.add(candidate);
        return candidate;
    }

    public synchronized List<ArtifactRuntimeBinding> registerAll(ArtifactRuntimeSource source,
                                                                  Collection<ArtifactRuntimeDescriptor> descriptors) {
        if (descriptors == null || descriptors.isEmpty()) {
            return List.of();
        }
        List<ArtifactRuntimeBinding> registered = new ArrayList<>();
        for (ArtifactRuntimeDescriptor descriptor : descriptors) {
            ArtifactRuntimeBinding binding = register(source, descriptor);
            if (binding != null && !registered.contains(binding)) {
                registered.add(binding);
            }
        }
        return List.copyOf(registered);
    }

    public synchronized List<ArtifactRuntimeBinding> listBindings() {
        return List.copyOf(bindings);
    }

    public synchronized List<ArtifactRuntimeBinding> findByToolCallId(String toolCallId) {
        if (StringUtils.isBlank(toolCallId)) {
            return List.of();
        }
        return bindings.stream()
                .filter(item -> item.getSource() != null && toolCallId.equals(item.getSource().getToolCallId()))
                .toList();
    }

    /** 仅在材料产物整批登记失败时撤回本轮运行期声明，防止末态误交付。 */
    public synchronized void removeByToolCallId(String toolCallId) {
        if (StringUtils.isNotBlank(toolCallId)) {
            bindings.removeIf(item -> item.getSource() != null
                    && toolCallId.equals(item.getSource().getToolCallId()));
        }
    }

    public synchronized List<ArtifactRuntimeBinding> listVisibleBindings() {
        return bindings.stream().filter(ArtifactRuntimeBinding::isVisible).toList();
    }

    public synchronized List<ArtifactRuntimeBinding> listDeliverableOutputs() {
        return bindings.stream().filter(ArtifactRuntimeBinding::isDeliverableOutput).toList();
    }

    public synchronized ArtifactRuntimeBinding findByArtifactKey(String artifactKey) {
        if (StringUtils.isBlank(artifactKey)) {
            return null;
        }
        return bindings.stream().filter(item -> artifactKey.equals(item.artifactKey())).findFirst().orElse(null);
    }

    private boolean sameIdentity(ArtifactRuntimeBinding left, ArtifactRuntimeBinding right) {
        if (left == null || right == null || left.getSource() == null || right.getSource() == null
                || left.getDescriptor() == null || right.getDescriptor() == null
                || !Objects.equals(left.getSource().sourceKey(), right.getSource().sourceKey())) {
            return false;
        }
        String leftIdentity = descriptorIdentity(left.getDescriptor());
        String rightIdentity = descriptorIdentity(right.getDescriptor());
        return StringUtils.isNotBlank(leftIdentity) && leftIdentity.equals(rightIdentity);
    }

    private String descriptorIdentity(ArtifactRuntimeDescriptor descriptor) {
        if (StringUtils.isNotBlank(descriptor.getArtifactId())) {
            return "artifactId:" + descriptor.getArtifactId();
        }
        if (StringUtils.isNotBlank(descriptor.getStorageKey())) {
            return "storageKey:" + descriptor.getStorageKey();
        }
        if (StringUtils.isNotBlank(descriptor.getStorageRef()) && StringUtils.isNotBlank(descriptor.getFileName())) {
            return "storageRef:" + descriptor.getStorageRef() + "|" + descriptor.getFileName();
        }
        return null;
    }
}
