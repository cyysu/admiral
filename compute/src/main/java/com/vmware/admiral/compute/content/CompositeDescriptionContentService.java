/*
 * Copyright (c) 2016 VMware, Inc. All Rights Reserved.
 *
 * This product is licensed to you under the Apache License, Version 2.0 (the "License").
 * You may not use this product except in compliance with the License.
 *
 * This product may include a number of subcomponents with separate copyright notices
 * and license terms. Your use of these subcomponents is subject to the terms and
 * conditions of the subcomponent's license, as noted in the LICENSE file.
 */

package com.vmware.admiral.compute.content;

import static com.vmware.admiral.common.util.AssertUtil.assertNotEmpty;
import static com.vmware.admiral.common.util.AssertUtil.assertNotNull;
import static com.vmware.admiral.common.util.AssertUtil.assertNotNullOrEmpty;
import static com.vmware.admiral.common.util.OperationUtil.isApplicationYamlContent;
import static com.vmware.admiral.common.util.UriUtilsExtended.MEDIA_TYPE_APPLICATION_YAML;
import static com.vmware.admiral.common.util.ValidationUtils.handleValidationException;
import static com.vmware.admiral.compute.content.CompositeTemplateUtil.FORMATTER;
import static com.vmware.admiral.compute.content.CompositeTemplateUtil.assertContainersComponentsOnly;
import static com.vmware.admiral.compute.content.CompositeTemplateUtil.convertCompositeDescriptionToCompositeTemplate;
import static com.vmware.admiral.compute.content.CompositeTemplateUtil.deserializeCompositeTemplate;
import static com.vmware.admiral.compute.content.CompositeTemplateUtil.deserializeDockerCompose;
import static com.vmware.admiral.compute.content.CompositeTemplateUtil.fromCompositeTemplateToCompositeDescription;
import static com.vmware.admiral.compute.content.CompositeTemplateUtil.fromCompositeTemplateToDockerCompose;
import static com.vmware.admiral.compute.content.CompositeTemplateUtil.fromDockerComposeToCompositeTemplate;
import static com.vmware.admiral.compute.content.CompositeTemplateUtil.getYamlType;
import static com.vmware.admiral.compute.content.CompositeTemplateUtil.serializeCompositeTemplate;
import static com.vmware.admiral.compute.content.CompositeTemplateUtil.serializeDockerCompose;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.vmware.admiral.common.ManagementUriParts;
import com.vmware.admiral.compute.ResourceType;
import com.vmware.admiral.compute.container.CompositeComponentRegistry;
import com.vmware.admiral.compute.container.CompositeDescriptionFactoryService;
import com.vmware.admiral.compute.container.CompositeDescriptionService.CompositeDescription;
import com.vmware.admiral.compute.content.CompositeTemplateUtil.YamlType;
import com.vmware.admiral.compute.content.compose.DockerCompose;
import com.vmware.admiral.compute.kubernetes.KubernetesDescriptionContentService;
import com.vmware.admiral.compute.kubernetes.KubernetesDescriptionService;
import com.vmware.admiral.compute.kubernetes.KubernetesDescriptionService.KubernetesDescription;
import com.vmware.xenon.common.DeferredResult;
import com.vmware.xenon.common.LocalizableValidationException;
import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.OperationJoin;
import com.vmware.xenon.common.ServiceDocument;
import com.vmware.xenon.common.StatelessService;
import com.vmware.xenon.common.UriUtils;
import com.vmware.xenon.common.Utils;

/**
 * Service for parsing a composite template and creating a CompositeDescription (and all its
 * components)
 * <p>
 * TODO need to implement a TaskService that handles creating all the objects and cleaning up on
 * failure
 */
public class CompositeDescriptionContentService extends StatelessService {
    public static final String SELF_LINK = ManagementUriParts.COMPOSITE_DESC_CONTENT;

    public static final String SELF_LINK_PARAM_NAME = "selfLink";
    public static final String FORMAT_PARAM_NAME = "format";
    public static final String DISPOSITION_PARAM_NAME = "disposition";

    // Header to try to force the download of the file on the browser side with 'attachment' and a
    // default name for the file with 'filename='. But different browsers may behave differently.
    public static final String CONTENT_DISPOSITION_HEADER = "Content-Disposition";
    public static final String CONTENT_DISPOSITION_INLINE = "inline";
    public static final String CONTENT_DISPOSITION_ATTACHMENT = "attachment";
    public static final String CONTENT_DISPOSITION_FILENAME = "; filename=\"template.yaml\"";

    public static final String FORMAT_DOCKER_COMPOSE_TYPE = "Docker";

    public static final String KUBERNETES_APPLICATION_TEMPLATE_PREFIX = "Kubernetes Application ";

    @Override
    public void handleGet(Operation op) {
        Map<String, String> queryParams = UriUtils.parseUriQueryParams(op.getUri());
        String selfLink = queryParams.get(SELF_LINK_PARAM_NAME);
        assertNotEmpty(selfLink, SELF_LINK_PARAM_NAME);
        if (!selfLink.startsWith(UriUtils.URI_PATH_CHAR)) {
            selfLink = UriUtils
                    .buildUriPath(CompositeDescriptionFactoryService.SELF_LINK, selfLink);
        }

        // return YAML as Composite Template by default
        boolean returnDocker = FORMAT_DOCKER_COMPOSE_TYPE.equalsIgnoreCase(
                queryParams.get(FORMAT_PARAM_NAME));

        // return YAML as downloadable attachment by default
        boolean returnInline = CONTENT_DISPOSITION_INLINE.equalsIgnoreCase(
                queryParams.get(DISPOSITION_PARAM_NAME));

        sendRequest(Operation.createGet(this, selfLink).setCompletion((o, ex) -> {
            if (ex != null) {
                op.fail(ex);
                return;
            }

            CompositeDescription description = o.getBody(CompositeDescription.class);

            if (containsKubernetesDescriptions(description)) {
                processCompositeDescriptionWithKubernetes(description, op, returnInline);
            } else {
                convertCompositeDescriptionToCompositeTemplate(this, description).thenAccept(
                        template -> serializeAndComplete(template, returnDocker, returnInline, op))
                        .exceptionally(e -> {
                            op.fail(e);
                            return null;
                        });
            }
        }));
    }

    private void serializeAndComplete(CompositeTemplate template, boolean returnDocker,
            boolean returnInline, Operation op) {
        try {
            String content;
            if (returnDocker) {
                DockerCompose compose = fromCompositeTemplateToDockerCompose(template);
                content = serializeDockerCompose(compose);
            } else {
                content = serializeCompositeTemplate(template);
            }
            op.setBody(content);
            op.setContentType(MEDIA_TYPE_APPLICATION_YAML);

            String contentDisposition = (returnInline
                    ? CONTENT_DISPOSITION_INLINE : CONTENT_DISPOSITION_ATTACHMENT)
                    + CONTENT_DISPOSITION_FILENAME;

            op.addResponseHeader(CONTENT_DISPOSITION_HEADER, contentDisposition);

            op.complete();
        } catch (Exception e) {
            op.fail(e);
        }
    }

    private void processCompositeDescriptionWithKubernetes(CompositeDescription description,
            Operation op, boolean returnInline) {
        List<Operation> getDescOps = description.descriptionLinks.stream()
                .map(l -> Operation.createGet(this, l)).collect(Collectors.toList());

        OperationJoin.create(getDescOps)
                .setCompletion((ops, errors) -> {
                    if (errors != null) {
                        List<Throwable> throwables = errors.values().stream()
                                .filter(e -> e != null)
                                .collect(Collectors.toList());
                        op.fail(throwables.get(0));
                        throwables.stream().skip(1)
                                .forEach(e -> logWarning("%s", e.getMessage()));
                    } else {
                        StringBuilder builder = new StringBuilder();
                        ops.values().forEach(o -> {
                            KubernetesDescription desc = o.getBody(KubernetesDescription.class);
                            builder.append(desc.kubernetesEntity);
                            builder.append("\n");
                        });
                        op.setBody(builder.toString().trim());
                        op.setContentType(MEDIA_TYPE_APPLICATION_YAML);
                        String contentDisposition = (returnInline
                                ? CONTENT_DISPOSITION_INLINE : CONTENT_DISPOSITION_ATTACHMENT)
                                + CONTENT_DISPOSITION_FILENAME;

                        op.addResponseHeader(CONTENT_DISPOSITION_HEADER, contentDisposition);
                        op.complete();
                    }
                }).sendWith(this);
    }

    private boolean containsKubernetesDescriptions(CompositeDescription description) {
        if (description.descriptionLinks == null || description.descriptionLinks.size() < 1) {
            return false;
        }
        for (String link : description.descriptionLinks) {
            if (!link.startsWith(KubernetesDescriptionService.FACTORY_LINK)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void handlePost(Operation op) {
        if (!op.hasBody()) {
            op.fail(new LocalizableValidationException("body is required",
                    "compute.body.required"));
            return;
        }

        CompositeTemplate template = null;
        String content = null;
        YamlType yamlType = null;
        try {
            if (isApplicationYamlContent(op.getContentType())) {
                content = op.getBody(String.class);
                yamlType = getYamlType(content);
                switch (yamlType) {
                case COMPOSITE_TEMPLATE:
                    template = deserializeCompositeTemplate(content);
                    break;
                case DOCKER_COMPOSE:
                    DockerCompose compose = deserializeDockerCompose(content);
                    template = fromDockerComposeToCompositeTemplate(compose);
                    break;
                case KUBERNETES_TEMPLATE:
                    break;
                default:
                    throw new LocalizableValidationException(
                            "Unknown YAML content type! Only Blueprint and Docker Compose v2 formats are supported.",
                            "compute.content.unknown.yaml.type");
                }
            } else {
                try {
                    template = op.getBody(CompositeTemplate.class);
                } catch (Exception e) {
                    logWarning("Failed to deserialize CompositeTemplate serialized content! %s",
                            Utils.toString(e));
                    throw new LocalizableValidationException(
                            "Failed to deserialize CompositeTemplate serialized content!",
                            "compute.content.deserialize.template");
                }
            }

        } catch (Exception e) {
            handleValidationException(op, e);
            return;
        }

        if (YamlType.KUBERNETES_TEMPLATE == yamlType) {
            processKubernetesTemplate(content, op);
        } else {
            processCompositeTemplate(template, op);
        }

    }

    private void processCompositeTemplate(CompositeTemplate template, Operation op) {
        validateCompositeTemplate(template);

        Operation createDescriptionOp = Operation.createPost(this,
                CompositeDescriptionFactoryService.SELF_LINK);

        DeferredResult<List<Operation>> components = createComponents(template);

        DeferredResult<Object> handle = components.thenCompose((ops) -> {
            CompositeDescription description = fromCompositeTemplateToCompositeDescription(
                    template);
            description.descriptionLinks = ops.stream()
                    .map((o) -> o.getBody(ServiceDocument.class).documentSelfLink)
                    .collect(Collectors.toList());

            createDescriptionOp.setBody(description).setReferer(getUri());
            return getHost().sendWithDeferredResult(createDescriptionOp);
        }).handle((o, e) -> {
            if (e != null) {
                logWarning("Failed to create CompositeDescription: %s", Utils.toString(e));
                LocalizableValidationException ex = new LocalizableValidationException(e,
                        "Failed to create CompositeDescription: " + Utils.toString(e),
                        "compute.composite-description.create.failed");
                op.fail(ex);
                return null;
            } else {
                CompositeDescription description = o.getBody(CompositeDescription.class);
                op.addResponseHeader(Operation.LOCATION_HEADER,
                        description.documentSelfLink);
                op.complete();
            }
            return null;
        });

        if (handle.isDone()) {
            // do something with the deferred result because findbugs complains
        }
    }

    private void processKubernetesTemplate(String yamlContent, Operation post) {
        assertNotNullOrEmpty(yamlContent, "yamlContent");

        sendRequest(Operation.createPost(this, KubernetesDescriptionContentService.SELF_LINK)
                .setBody(yamlContent)
                .setCompletion((o, ex) -> {
                    if (ex != null) {
                        post.setContentType(Operation.MEDIA_TYPE_APPLICATION_JSON);
                        post.setStatusCode(Operation.STATUS_CODE_BAD_REQUEST);
                        post.fail(ex, Utils.toServiceErrorResponse(ex));
                    } else {
                        String[] resourceLinks = o.getBody(String[].class);
                        CompositeDescription description = new CompositeDescription();
                        description.descriptionLinks = Arrays.asList(resourceLinks);
                        description.name = KUBERNETES_APPLICATION_TEMPLATE_PREFIX + ZonedDateTime
                                .now(ZoneOffset.UTC).format(FORMATTER);
                        Operation createCompositeDescription = Operation
                                .createPost(this, CompositeDescriptionFactoryService.SELF_LINK)
                                .setBody(description)
                                .setCompletion((op, err) -> {
                                    if (err != null) {
                                        post.fail(err);
                                    } else {
                                        CompositeDescription createdDescription = op.getBody
                                                (CompositeDescription.class);
                                        post.addResponseHeader(Operation.LOCATION_HEADER,
                                                createdDescription.documentSelfLink);
                                        post.complete();
                                    }
                                });

                        sendRequest(createCompositeDescription);
                    }
                }));
    }

    private DeferredResult<List<Operation>> createComponents(CompositeTemplate compositeTemplate) {

        List<DeferredResult<Operation>> results = new ArrayList<>();
        compositeTemplate.components.values().stream()
                .forEach(component -> results.add(persistComponent(component)));

        return DeferredResult.allOf(results);
    }

    private DeferredResult<Operation> persistComponent(ComponentTemplate<?> component) {
        NestedState nestedState = new NestedState();
        nestedState.object = (ServiceDocument) component.data;
        nestedState.children = component.children;

        ResourceType resourceType = ResourceType.fromContentType(component.type);
        String factoryLink = CompositeComponentRegistry.factoryLinkByType(resourceType.getName());
        nestedState.factoryLink = factoryLink;

        return nestedState.sendRequest(this, Action.POST);
    }

    private void validateCompositeTemplate(CompositeTemplate compositeTemplate) {
        assertNotNull(compositeTemplate, "compositeTemplate");
        assertNotEmpty(compositeTemplate.name, "name");
        assertContainersComponentsOnly(compositeTemplate.components);
    }

}
