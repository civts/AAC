package it.smartcommunitylab.aac.dev;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.yaml.snakeyaml.Yaml;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.jsonSchema.JsonSchema;

import it.smartcommunitylab.aac.SystemKeys;
import it.smartcommunitylab.aac.common.NoSuchClientException;
import it.smartcommunitylab.aac.common.NoSuchProviderException;
import it.smartcommunitylab.aac.common.NoSuchRealmException;
import it.smartcommunitylab.aac.common.NoSuchUserException;
import it.smartcommunitylab.aac.common.SystemException;
import it.smartcommunitylab.aac.core.ClientManager;
import it.smartcommunitylab.aac.core.ProviderManager;
import it.smartcommunitylab.aac.core.base.ConfigurableIdentityProvider;
import it.smartcommunitylab.aac.core.base.ConfigurableProvider;
import it.smartcommunitylab.aac.model.ClientApp;
import springfox.documentation.annotations.ApiIgnore;

@RestController
@ApiIgnore
@RequestMapping("/console/dev")
public class DevIdentityProviderController {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final TypeReference<Map<String, List<ConfigurableIdentityProvider>>> typeRef = new TypeReference<Map<String, List<ConfigurableIdentityProvider>>>() {
    };

    @Autowired
    private ProviderManager providerManager;

    @Autowired
    private ClientManager clientManager;

    @Autowired
    @Qualifier("yamlObjectMapper")
    private ObjectMapper yamlObjectMapper;

    /*
     * Providers
     */

    @GetMapping("/realms/{realm}/idps")
    public ResponseEntity<Collection<ConfigurableIdentityProvider>> getRealmProviders(
            @PathVariable @Valid @Pattern(regexp = SystemKeys.SLUG_PATTERN) String realm)
            throws NoSuchRealmException {

        List<ConfigurableIdentityProvider> providers = providerManager
                .listIdentityProviders(realm)
                .stream()
                .map(cp -> {
                    cp.setRegistered(providerManager.isProviderRegistered(realm, cp));
                    return cp;
                }).collect(Collectors.toList());

        return ResponseEntity.ok(providers);
    }

    @GetMapping("/realms/{realm}/providertemplates")
    public ResponseEntity<Collection<ConfigurableProvider>> getRealmProviderTemplates(
            @PathVariable @Valid @Pattern(regexp = SystemKeys.SLUG_PATTERN) String realm)
            throws NoSuchRealmException {

        List<ConfigurableProvider> providers = providerManager
                .listProviderConfigurationTemplates(realm, ConfigurableProvider.TYPE_IDENTITY)
                .stream()
//                .map(cp -> {
//                    cp.setRegistered(providerManager.isProviderRegistered(cp));
//                    return cp;
//                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(providers);
    }

    @GetMapping("/realms/{realm}/idps/{providerId}")
    public ResponseEntity<ConfigurableIdentityProvider> getRealmProvider(
            @PathVariable @Valid @Pattern(regexp = SystemKeys.SLUG_PATTERN) String realm,
            @PathVariable @Valid @Pattern(regexp = SystemKeys.SLUG_PATTERN) String providerId)
            throws NoSuchProviderException, NoSuchRealmException {
        ConfigurableIdentityProvider provider = providerManager.getIdentityProvider(realm, providerId);

        // check if registered
        boolean isRegistered = providerManager.isProviderRegistered(realm, provider);
        provider.setRegistered(isRegistered);

//        // if registered fetch active configuration
//        if (isRegistered) {
//            IdentityProvider idp = providerManager.getIdentityProvider(providerId);
//            Map<String, Serializable> configMap = idp.getConfiguration().getConfiguration();
//            // we replace config instead of merging, when active config can not be
//            // modified anyway
//            provider.setConfiguration(configMap);
//        }

        // fetch also configuration schema
        JsonSchema schema = providerManager.getConfigurationSchema(realm, provider.getType(), provider.getAuthority());
        provider.setSchema(schema);

        return ResponseEntity.ok(provider);
    }

    @DeleteMapping("/realms/{realm}/idps/{providerId}")
    public ResponseEntity<Void> deleteRealmProvider(
            @PathVariable @Valid @Pattern(regexp = SystemKeys.SLUG_PATTERN) String realm,
            @PathVariable @Valid @Pattern(regexp = SystemKeys.SLUG_PATTERN) String providerId)
            throws NoSuchRealmException, NoSuchUserException, SystemException, NoSuchProviderException {
        providerManager.deleteIdentityProvider(realm, providerId);
        return ResponseEntity.ok(null);
    }

    @PostMapping("/realms/{realm}/idps")
    public ResponseEntity<ConfigurableIdentityProvider> createRealmProvider(
            @PathVariable @Valid @Pattern(regexp = SystemKeys.SLUG_PATTERN) String realm,
            @Valid @RequestBody ConfigurableIdentityProvider registration)
            throws NoSuchRealmException, NoSuchUserException, SystemException, NoSuchProviderException {
        // unpack and build model
        String authority = registration.getAuthority();
        String name = registration.getName();
        String description = registration.getDescription();
        String persistence = registration.getPersistence();
        String events = registration.getEvents();
        boolean linkable = registration.isLinkable();

        Map<String, Serializable> configuration = registration.getConfiguration();

        ConfigurableIdentityProvider provider = new ConfigurableIdentityProvider(authority, null, realm);
        provider.setName(name);
        provider.setDescription(description);
        provider.setEnabled(false);
        provider.setPersistence(persistence);
        provider.setLinkable(linkable);
        provider.setEvents(events);
        provider.setConfiguration(configuration);

        provider = providerManager.addIdentityProvider(realm, provider);

        // fetch also configuration schema
        JsonSchema schema = providerManager.getConfigurationSchema(realm, provider.getType(), provider.getAuthority());
        provider.setSchema(schema);

        return ResponseEntity.ok(provider);
    }

    @PutMapping("/realms/{realm}/idps/{providerId}")
    public ResponseEntity<ConfigurableIdentityProvider> updateRealmProvider(
            @PathVariable @Valid @Pattern(regexp = SystemKeys.SLUG_PATTERN) String realm,
            @PathVariable @Valid @Pattern(regexp = SystemKeys.SLUG_PATTERN) String providerId,
            @Valid @RequestBody ConfigurableIdentityProvider registration)
            throws NoSuchRealmException, NoSuchUserException, SystemException, NoSuchProviderException {

        ConfigurableIdentityProvider provider = providerManager.getIdentityProvider(realm, providerId);

        // we update only configuration
        String name = registration.getName();
        String description = registration.getDescription();
        String displayMode = registration.getDisplayMode();
        String persistence = registration.getPersistence();
        String events = registration.getEvents();
        boolean linkable = registration.isLinkable();

        Map<String, Serializable> configuration = registration.getConfiguration();
        Map<String, String> hookFunctions = registration.getHookFunctions();

        provider.setName(name);
        provider.setDescription(description);
        provider.setDisplayMode(displayMode);

        provider.setPersistence(persistence);
        provider.setLinkable(linkable);

        provider.setEvents(events);
        provider.setConfiguration(configuration);
        provider.setHookFunctions(hookFunctions);

        provider = providerManager.updateIdentityProvider(realm, providerId, provider);

        // check if registered
        boolean isRegistered = providerManager.isProviderRegistered(realm, provider);
        provider.setRegistered(isRegistered);

        // fetch also configuration schema
        JsonSchema schema = providerManager.getConfigurationSchema(realm, provider.getType(), provider.getAuthority());
        provider.setSchema(schema);

        return ResponseEntity.ok(provider);
    }

    @PutMapping("/realms/{realm}/idps/{providerId}/state")
    public ResponseEntity<ConfigurableIdentityProvider> updateRealmProviderState(
            @PathVariable @Valid @Pattern(regexp = SystemKeys.SLUG_PATTERN) String realm,
            @PathVariable @Valid @Pattern(regexp = SystemKeys.SLUG_PATTERN) String providerId,
            @RequestBody ConfigurableIdentityProvider registration)
            throws NoSuchRealmException, NoSuchUserException, SystemException, NoSuchProviderException {

        ConfigurableIdentityProvider provider = providerManager.getIdentityProvider(realm, providerId);
        boolean enabled = registration.isEnabled();

        if (enabled) {
            provider = providerManager.registerIdentityProvider(realm, providerId);
        } else {
            provider = providerManager.unregisterIdentityProvider(realm, providerId);
        }

        // check if registered
        boolean isRegistered = providerManager.isProviderRegistered(realm, provider);
        provider.setRegistered(isRegistered);

        return ResponseEntity.ok(provider);
    }

    @GetMapping("/realms/{realm}/idps/{providerId}/export")
    public void exportRealmProvider(
            @PathVariable @Valid @Pattern(regexp = SystemKeys.SLUG_PATTERN) String realm,
            @PathVariable @Valid @Pattern(regexp = SystemKeys.SLUG_PATTERN) String providerId,
            HttpServletResponse res)
            throws NoSuchProviderException, NoSuchRealmException, SystemException, IOException {
        ConfigurableIdentityProvider provider = providerManager.getIdentityProvider(realm, providerId);

//      String s = yaml.dump(clientApp);
        String s = yamlObjectMapper.writeValueAsString(provider);

        // write as file
        res.setContentType("text/yaml");
        res.setHeader("Content-Disposition", "attachment;filename=idp-" + provider.getName() + ".yaml");
        ServletOutputStream out = res.getOutputStream();
        out.print(s);
        out.flush();
        out.close();

    }

    @PutMapping("/realms/{realm}/idps")
    public ResponseEntity<Collection<ConfigurableIdentityProvider>> importRealmProvider(
            @PathVariable @Valid @Pattern(regexp = SystemKeys.SLUG_PATTERN) String realm,
            @RequestParam("file") @Valid @NotNull @NotBlank MultipartFile file) throws Exception {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("empty file");
        }

        if (file.getContentType() != null &&
                (!file.getContentType().equals(SystemKeys.MEDIA_TYPE_YAML.toString())
                        && !file.getContentType().equals(SystemKeys.MEDIA_TYPE_YML.toString())
                        && !file.getContentType().equals(SystemKeys.MEDIA_TYPE_XYAML.toString()))) {
            throw new IllegalArgumentException("invalid file");
        }
        try {
            List<ConfigurableIdentityProvider> providers = new ArrayList<>();
            boolean multiple = false;

            // read as raw yaml to check if collection
            Yaml yaml = new Yaml();
            Map<String, Object> obj = yaml.load(file.getInputStream());
            multiple = obj.containsKey("providers");

            if (multiple) {
                Map<String, List<ConfigurableIdentityProvider>> list = yamlObjectMapper.readValue(file.getInputStream(),
                        typeRef);

                for (ConfigurableIdentityProvider registration : list.get("providers")) {
                    // unpack and build model
                    String id = registration.getProvider();
                    String authority = registration.getAuthority();
                    String name = registration.getName();
                    String description = registration.getDescription();
                    String displayMode = registration.getDisplayMode();
                    String persistence = registration.getPersistence();
                    String events = registration.getEvents();
                    Map<String, Serializable> configuration = registration.getConfiguration();
                    Map<String, String> hookFunctions = registration.getHookFunctions();

                    ConfigurableIdentityProvider provider = new ConfigurableIdentityProvider(authority, id, realm);
                    provider.setName(name);
                    provider.setDescription(description);
                    provider.setDisplayMode(displayMode);
                    provider.setEnabled(false);
                    provider.setPersistence(persistence);
                    provider.setEvents(events);
                    provider.setConfiguration(configuration);
                    provider.setHookFunctions(hookFunctions);

                    provider = providerManager.addIdentityProvider(realm, provider);

                    // fetch also configuration schema
                    JsonSchema schema = providerManager.getConfigurationSchema(realm, provider.getType(),
                            provider.getAuthority());
                    provider.setSchema(schema);
                    providers.add(provider);
                }
            } else {
                // try single element
                ConfigurableIdentityProvider registration = yamlObjectMapper.readValue(file.getInputStream(),
                        ConfigurableIdentityProvider.class);

                // unpack and build model
                String id = registration.getProvider();
                String authority = registration.getAuthority();
                String type = registration.getType();
                String name = registration.getName();
                String description = registration.getDescription();
                String displayMode = registration.getDisplayMode();
                String persistence = registration.getPersistence();
                String events = registration.getEvents();
                Map<String, Serializable> configuration = registration.getConfiguration();
                Map<String, String> hookFunctions = registration.getHookFunctions();

                ConfigurableIdentityProvider provider = new ConfigurableIdentityProvider(authority, id, realm);
                provider.setName(name);
                provider.setDescription(description);
                provider.setDisplayMode(displayMode);
                provider.setType(type);
                provider.setEnabled(false);
                provider.setPersistence(persistence);
                provider.setEvents(events);
                provider.setConfiguration(configuration);
                provider.setHookFunctions(hookFunctions);

                provider = providerManager.addIdentityProvider(realm, provider);

                // fetch also configuration schema
                JsonSchema schema = providerManager.getConfigurationSchema(realm, provider.getType(),
                        provider.getAuthority());
                provider.setSchema(schema);
                providers.add(provider);
            }
            return ResponseEntity.ok(providers);

        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }

    }

    @PutMapping("/realms/{realm}/idps/{providerId}/apps/{clientId}")
    public ResponseEntity<ClientApp> updateRealmProviderClientApp(
            @PathVariable @Valid @Pattern(regexp = SystemKeys.SLUG_PATTERN) String realm,
            @PathVariable @Valid @Pattern(regexp = SystemKeys.SLUG_PATTERN) String providerId,
            @PathVariable @Valid @Pattern(regexp = SystemKeys.SLUG_PATTERN) String clientId,
            @RequestBody ClientApp app)
            throws NoSuchRealmException, NoSuchUserException, NoSuchClientException, SystemException,
            NoSuchProviderException {

        ClientApp clientApp = clientManager.getClientApp(realm, clientId);
        // update providers only for this id
        Set<String> providers = new HashSet<>(Arrays.asList(clientApp.getProviders()));
        boolean enabled = Arrays.stream(app.getProviders()).anyMatch(p -> providerId.equals(p));
        if (enabled) {
            if (!providers.contains(providerId)) {
                providers.add(providerId);
                clientApp.setProviders(providers.toArray(new String[0]));
                clientApp = clientManager.updateClientApp(realm, clientId, clientApp);
            }
        } else {
            if (providers.contains(providerId)) {
                providers.remove(providerId);
                clientApp.setProviders(providers.toArray(new String[0]));
                clientApp = clientManager.updateClientApp(realm, clientId, clientApp);
            }
        }

        return ResponseEntity.ok(clientApp);
    }
}
