package it.smartcommunitylab.aac.bootstrap;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import it.smartcommunitylab.aac.SystemKeys;
import it.smartcommunitylab.aac.common.NoSuchRealmException;
import it.smartcommunitylab.aac.core.AuthorityManager;
import it.smartcommunitylab.aac.core.ClientManager;
import it.smartcommunitylab.aac.core.ProviderManager;
import it.smartcommunitylab.aac.core.RealmManager;
import it.smartcommunitylab.aac.core.UserManager;
import it.smartcommunitylab.aac.core.authorities.AttributeAuthority;
import it.smartcommunitylab.aac.core.authorities.IdentityAuthority;
import it.smartcommunitylab.aac.core.base.ConfigurableAttributeProvider;
import it.smartcommunitylab.aac.core.base.ConfigurableIdentityProvider;
import it.smartcommunitylab.aac.core.base.ConfigurableProvider;
import it.smartcommunitylab.aac.core.persistence.UserEntity;
import it.smartcommunitylab.aac.core.service.AttributeProviderService;
import it.smartcommunitylab.aac.core.service.IdentityProviderService;
import it.smartcommunitylab.aac.core.service.UserEntityService;
import it.smartcommunitylab.aac.crypto.PasswordHash;
import it.smartcommunitylab.aac.internal.persistence.InternalUserAccount;
import it.smartcommunitylab.aac.internal.service.InternalUserAccountService;
import it.smartcommunitylab.aac.model.ClientApp;
import it.smartcommunitylab.aac.model.Realm;
import it.smartcommunitylab.aac.services.Service;
import it.smartcommunitylab.aac.services.ServicesManager;

@Component
public class AACBootstrap {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Value("${bootstrap.apply}")
    private boolean apply;

    @Value("${bootstrap.file}")
    private String source;

    @Value("${admin.username}")
    private String adminUsername;

    @Autowired
    private ResourceLoader resourceLoader;

    @Autowired
    @Qualifier("yamlObjectMapper")
    private ObjectMapper yamlObjectMapper;

//    @Autowired
    private BootstrapConfig config;

    @Autowired
    private AuthorityManager authorityManager;

    @Autowired
    private RealmManager realmManager;

    @Autowired
    private ProviderManager providerManager;

    @Autowired
    private ClientManager clientManager;

    @Autowired
    private ServicesManager serviceManager;

    @Autowired
    private UserManager userManager;

    @Autowired
    private UserEntityService userService;

    @Autowired
    private InternalUserAccountService internalUserService;

    @Autowired
    private IdentityProviderService identityProviderService;

    @Autowired
    private AttributeProviderService attributeProviderService;

    @EventListener
    public void onApplicationEvent(ApplicationReadyEvent event) {
        try {
            // base initialization
            logger.debug("application init");
            initServices();

            // build a security context as admin to bootstrap configs
            // initContext(adminUsername);

            // bootstrap providers
            // TODO use a dedicated thread, or a multithread
            bootstrapSystemProviders();
            bootstrapIdentityProviders();
            bootstrapAttributeProviders();

            // custom bootstrap
            if (apply) {
                logger.debug("application bootstrap");
                bootstrapConfig();
            } else {
                logger.debug("bootstrap disabled by config");
            }

        } catch (Exception e) {
            logger.error("error bootstrapping: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void bootstrapSystemProviders() throws NoSuchRealmException {
        Map<String, IdentityAuthority> ias = authorityManager.listIdentityAuthorities().stream()
                .collect(Collectors.toMap(a -> a.getAuthorityId(), a -> a));

        Collection<ConfigurableIdentityProvider> idps = identityProviderService.listProviders(SystemKeys.REALM_SYSTEM);

        for (ConfigurableIdentityProvider idp : idps) {
            // try register
            if (idp.isEnabled()) {
                try {
                    // register via authorityManager
//                        authorityManager.registerIdentityProvider(idp);

                    // register directly with authority
                    IdentityAuthority ia = ias.get(idp.getAuthority());
                    if (ia == null) {
                        throw new IllegalArgumentException(
                                "no authority for " + String.valueOf(idp.getAuthority()));
                    }

                    ia.registerIdentityProvider(idp);
                } catch (Exception e) {
                    logger.error("error registering provider " + idp.getProvider() + " for realm "
                            + idp.getRealm() + ": " + e.getMessage());

                    if (logger.isTraceEnabled()) {
                        e.printStackTrace();
                    }
                }
            }
        }

    }

    private void bootstrapIdentityProviders() {
        Map<String, IdentityAuthority> ias = authorityManager.listIdentityAuthorities().stream()
                .collect(Collectors.toMap(a -> a.getAuthorityId(), a -> a));

        // load all realm providers from storage
        Collection<Realm> realms = realmManager.listRealms();

        // we iterate by realm to load consistently each realm
        // we use parallel to leverage default threadpool, loading should be thread-safe
        realms.parallelStream().forEach(realm -> {
            Collection<ConfigurableIdentityProvider> idps = identityProviderService.listProviders(realm.getSlug());

            for (ConfigurableIdentityProvider idp : idps) {
                // try register
                if (idp.isEnabled()) {
                    try {
                        // register via authorityManager
//                        authorityManager.registerIdentityProvider(idp);

                        // register directly with authority
                        IdentityAuthority ia = ias.get(idp.getAuthority());
                        if (ia == null) {
                            throw new IllegalArgumentException(
                                    "no authority for " + String.valueOf(idp.getAuthority()));
                        }

                        ia.registerIdentityProvider(idp);
                    } catch (Exception e) {
                        logger.error("error registering provider " + idp.getProvider() + " for realm "
                                + idp.getRealm() + ": " + e.getMessage());

                        if (logger.isTraceEnabled()) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        });
    }

    private void bootstrapAttributeProviders() {
        Map<String, AttributeAuthority> ias = authorityManager.listAttributeAuthorities().stream()
                .collect(Collectors.toMap(a -> a.getAuthorityId(), a -> a));

        // load all realm providers from storage
        Collection<Realm> realms = realmManager.listRealms();

        // we iterate by realm to load consistently each realm
        // we use parallel to leverage default threadpool, loading should be thread-safe
        realms.parallelStream().forEach(realm -> {
            Collection<ConfigurableAttributeProvider> providers = attributeProviderService
                    .listProviders(realm.getSlug());

            for (ConfigurableAttributeProvider provider : providers) {
                // try register
                if (provider.isEnabled()) {
                    try {
                        // register via authorityManager
//                        authorityManager.registerAttributeProvider(idp);

                        // register directly with authority
                        AttributeAuthority ia = ias.get(provider.getAuthority());
                        if (ia == null) {
                            throw new IllegalArgumentException(
                                    "no authority for " + String.valueOf(provider.getAuthority()));
                        }

                        ia.registerAttributeProvider(provider);
                    } catch (Exception e) {
                        logger.error("error registering provider " + provider.getProvider() + " for realm "
                                + provider.getRealm() + ": " + e.getMessage());

                        if (logger.isTraceEnabled()) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        });
    }

    // @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void bootstrapConfig() throws Exception {

        // read configuration
        Resource res = resourceLoader.getResource(source);
        if (!res.exists()) {
            logger.debug("no bootstrap file from " + source);
            return;
        }

        // read config
        config = yamlObjectMapper.readValue(res.getInputStream(), BootstrapConfig.class);

        // TODO validation on imported beans

        /*
         * Realms creation
         */
        logger.debug("create bootstrap realms");

        // keep a cache of bootstrapped realms, we
        // will process only content related to these realms
        Map<String, Realm> realms = new HashMap<>();

        for (Realm r : config.getRealms()) {

            try {
                if (!StringUtils.hasText(r.getSlug())) {
                    // we ask id to be provided otherwise we create a new one every time
                    logger.error("error creating realm, missing slug");
                    throw new IllegalArgumentException("missing slug");
                }

                logger.debug("create or update realm " + r.getSlug());

                Realm realm = realmManager.findRealm(r.getSlug());
                if (realm == null) {
                    realm = realmManager.addRealm(r);
                } else {
                    realm = realmManager.updateRealm(r.getSlug(), r);
                }

                // keep in cache
                realms.put(realm.getSlug(), realm);

            } catch (Exception e) {
                logger.error("error creating provider " + String.valueOf(r.getSlug()) + ": " + e.getMessage());
                e.printStackTrace();
            }
        }

        /*
         * IdP
         */
        // keep a cache, we'll load users only to these providers
        Map<String, ConfigurableProvider> providers = new HashMap<>();
        for (ConfigurableProvider cp : config.getProviders()) {

            try {
                if (!realms.containsKey(cp.getRealm())) {
                    // not managed here, skip
                    continue;
                }
                if (!StringUtils.hasText(cp.getProvider())) {
                    // we ask id to be provided otherwise we create a new one every time
                    logger.error("error creating provider, missing id");
                    throw new IllegalArgumentException("missing id");
                }
                // we support only idp for now
                if (SystemKeys.RESOURCE_IDENTITY.equals(cp.getType())) {
                    logger.debug("create or update provider " + cp.getProvider());
                    ConfigurableIdentityProvider provider = providerManager.findIdentityProvider(cp.getRealm(),
                            cp.getProvider());

//                    if (provider == null) {
//                        provider = providerManager.addIdentityProvider(cp.getRealm(), cp);
//                    } else {
//                        provider = providerManager.unregisterIdentityProvider(cp.getRealm(), cp.getProvider());
//                        provider = providerManager.updateIdentityProvider(cp.getRealm(), cp.getProvider(), cp);
//                    }

                    if (cp.isEnabled()) {
                        // register
                        if (!providerManager.isProviderRegistered(cp.getRealm(), provider)) {
                            provider = providerManager.registerIdentityProvider(provider.getRealm(),
                                    provider.getProvider());
                        }
                    }

                    // keep in cache
                    providers.put(provider.getProvider(), provider);

                }

            } catch (Exception e) {
                logger.error("error creating provider " + String.valueOf(cp.getProvider()) + ": " + e.getMessage());
                e.printStackTrace();
            }
        }

        /*
         * Services
         */
        for (Service s : config.getServices()) {

            try {
                if (!StringUtils.hasText(s.getRealm()) || !realms.containsKey(s.getRealm())) {
                    // not managed here, skip
                    continue;
                }
                if (!StringUtils.hasText(s.getServiceId())) {
                    // we ask id to be provided otherwise we create a new one every time
                    logger.error("error creating service, missing serviceId");
                    throw new IllegalArgumentException("missing serviceId");
                }

                if (!StringUtils.hasText(s.getNamespace())) {
                    logger.error("error creating service, missing namespace");
                    throw new IllegalArgumentException("missing namespace");
                }

                logger.debug("create or update service " + s.getServiceId());
                Service service = serviceManager.findService(s.getRealm(), s.getServiceId());

                if (service == null) {
                    service = serviceManager.addService(s.getRealm(), s);
                } else {
                    service = serviceManager.updateService(s.getRealm(), s.getServiceId(), s);
                }

            } catch (Exception e) {
                logger.error("error creating service " + String.valueOf(s.getServiceId()) + ": " + e.getMessage());
                e.printStackTrace();
            }
        }

        /*
         * ClientApp
         */
        for (ClientApp ca : config.getClients()) {

            try {
                if (!StringUtils.hasText(ca.getRealm()) || !realms.containsKey(ca.getRealm())) {
                    // not managed here, skip
                    continue;
                }
                if (!StringUtils.hasText(ca.getClientId())) {
                    // we ask id to be provided otherwise we create a new one every time
                    logger.error("error creating client, missing clientId");
                    throw new IllegalArgumentException("missing clientId");
                }

                logger.debug("create or update client " + ca.getClientId());
                ClientApp client = clientManager.findClientApp(ca.getRealm(), ca.getClientId());

                if (client == null) {
                    client = clientManager.registerClientApp(ca.getRealm(), ca);
                } else {
                    client = clientManager.updateClientApp(ca.getRealm(), ca.getClientId(), ca);
                }

            } catch (Exception e) {
                logger.error("error creating client " + String.valueOf(ca.getClientId()) + ": " + e.getMessage());
                e.printStackTrace();
            }
        }

        // Internal users

        for (InternalUserAccount ua : config.getUsers().getInternal()) {
            try {
                if (!StringUtils.hasText(ua.getRealm()) || !StringUtils.hasText(ua.getProvider())) {
                    // invalid, skip
                    continue;
                }
                if (!realms.containsKey(ua.getRealm()) || !providers.containsKey(ua.getProvider())) {
                    // not managed here, skip
                    continue;
                }
                if (!StringUtils.hasText(ua.getSubject())) {
                    // we ask id to be provided otherwise we create a new one every time
                    logger.error("error creating user, missing subjectId");
                    throw new IllegalArgumentException("missing subjectId");
                }
                if (!StringUtils.hasText(ua.getUsername())) {
                    // we ask id to be provided otherwise we create a new one every time
                    logger.error("error creating user, missing username");
                    throw new IllegalArgumentException("missing username");
                }
                if (!StringUtils.hasText(ua.getPassword())) {
                    // we ask id to be provided otherwise we create a new one every time
                    logger.error("error creating user, missing password");
                    throw new IllegalArgumentException("missing password");
                }

                logger.debug("create or update user " + ua.getSubject() + " with authority internal");

                // check if user exists, recreate if needed
                UserEntity user = userService.findUser(ua.getSubject());
                if (user == null) {
                    user = userService.addUser(ua.getSubject(), ua.getRealm(), ua.getUsername(), ua.getEmail());
                } else {
                    // check match
                    if (!user.getRealm().equals(ua.getRealm())) {
                        logger.error("error creating user, realm mismatch");
                        throw new IllegalArgumentException("realm mismatch");
                    }

                    user = userService.updateUser(ua.getSubject(), ua.getUsername(), ua.getEmail());
                }

                InternalUserAccount account = internalUserService.findAccountByUsername(ua.getRealm(),
                        ua.getUsername());

                if (account == null) {
                    account = internalUserService.addAccount(ua);
                } else {
                    account = internalUserService.updateAccount(account.getId(), account);
                }

                // re-set password
                String hash = PasswordHash.createHash(ua.getPassword());
                account.setPassword(hash);
                account.setChangeOnFirstAccess(false);

                // ensure account is unlocked
                account.setConfirmed(true);
                account.setConfirmationKey(null);
                account.setConfirmationDeadline(null);
                account.setResetKey(null);
                account.setResetDeadline(null);
                account = internalUserService.updateAccount(account.getId(), account);

            } catch (Exception e) {
                logger.error("error creating user " + String.valueOf(ua.getSubject()) + ": " + e.getMessage());
                e.printStackTrace();
            }
        }

        // TODO oidc/saml users
        // requires accountService extracted from idp to detach from repo

        /*
         * Migrations?
         */

    }

    /*
     * Call init on each service we expect services to be independent and to execute
     * in their own transaction to avoid rollback issues across services
     */
    public void initServices() throws Exception {
//        /*
//         * Base user
//         */
//        logger.trace("init user");
//        userManager.init();
//
//        logger.trace("init registration");
//        registrationManager.init();
//
//        /*
//         * Base roles
//         */
//        logger.trace("init roles");
//        roleManager.init();
//
//        /*
//         * Base services
//         */
//        logger.trace("init services");
//        serviceManager.init();
//
//        /*
//         * Base clients
//         */
//        logger.trace("init client");
//        clientManager.init();

    }

//
//    public void executeMigrations() {
//
//    }

}
