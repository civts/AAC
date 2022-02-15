package it.smartcommunitylab.aac.webauthn.service;

import java.security.SecureRandom;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.yubico.webauthn.AssertionRequest;
import com.yubico.webauthn.AssertionResult;
import com.yubico.webauthn.FinishAssertionOptions;
import com.yubico.webauthn.FinishRegistrationOptions;
import com.yubico.webauthn.RegistrationResult;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.StartAssertionOptions;
import com.yubico.webauthn.StartRegistrationOptions;
import com.yubico.webauthn.data.AuthenticatorAssertionResponse;
import com.yubico.webauthn.data.AuthenticatorAttestationResponse;
import com.yubico.webauthn.data.AuthenticatorSelectionCriteria;
import com.yubico.webauthn.data.AuthenticatorTransport;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.ClientAssertionExtensionOutputs;
import com.yubico.webauthn.data.ClientRegistrationExtensionOutputs;
import com.yubico.webauthn.data.PublicKeyCredential;
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions;
import com.yubico.webauthn.data.ResidentKeyRequirement;
import com.yubico.webauthn.data.UserIdentity;
import com.yubico.webauthn.data.UserVerificationRequirement;

import org.springframework.util.StringUtils;

import it.smartcommunitylab.aac.SystemKeys;
import it.smartcommunitylab.aac.core.service.SubjectService;
import it.smartcommunitylab.aac.model.Subject;
import it.smartcommunitylab.aac.webauthn.auth.WebAuthnAuthenticationException;
import it.smartcommunitylab.aac.webauthn.model.WebAuthnCredentialCreationInfo;
import it.smartcommunitylab.aac.webauthn.model.WebAuthnLoginResponse;
import it.smartcommunitylab.aac.webauthn.model.WebAuthnRegistrationResponse;
import it.smartcommunitylab.aac.webauthn.persistence.WebAuthnCredential;
import it.smartcommunitylab.aac.webauthn.persistence.WebAuthnCredentialsRepository;
import it.smartcommunitylab.aac.webauthn.persistence.WebAuthnUserAccount;

public class WebAuthnRpService {

    private WebAuthnUserAccountService webAuthnUserAccountService;

    private WebAuthnCredentialsRepository webAuthnCredentialsRepository;

    private SubjectService subjectService;

    private final RelyingParty rp;
    private final String provider;

    private static Long TIMEOUT = 9000L;
    private static SecureRandom random = new SecureRandom();

    private Map<String, WebAuthnCredentialCreationInfo> activeRegistrations = new ConcurrentHashMap<>();
    private Map<String, AssertionRequest> activeAuthentications = new ConcurrentHashMap<>();

    public WebAuthnRpService(RelyingParty rp,
            WebAuthnUserAccountService webAuthnUserAccountService,
            WebAuthnCredentialsRepository webAuthnCredentialsRepository,
            SubjectService subjectService,
            String provider) {
        this.rp = rp;
        this.provider = provider;
        this.webAuthnCredentialsRepository = webAuthnCredentialsRepository;
        this.webAuthnUserAccountService = webAuthnUserAccountService;
        this.subjectService = subjectService;
    }

    public WebAuthnRegistrationResponse startRegistration(String username, String realm,
            String displayName, Subject optSub) {
        final AuthenticatorSelectionCriteria authenticatorSelection = AuthenticatorSelectionCriteria.builder()
                .residentKey(ResidentKeyRequirement.REQUIRED).userVerification(UserVerificationRequirement.REQUIRED)
                .build();
        WebAuthnUserAccount existingAccount = webAuthnUserAccountService.findByProviderAndUsername(provider,
                username);
        if (existingAccount != null) {
            // TODO: civts, check if the user is already authenticated.
            // In that case, we should allow registering multiple credentials
            throw new WebAuthnAuthenticationException("_", "User already exists");
        }
        final UserIdentity user = generateUserIdentity(username, realm, displayName, optSub);
        final StartRegistrationOptions startRegistrationOptions = StartRegistrationOptions.builder().user(user)
                .authenticatorSelection(authenticatorSelection).timeout(TIMEOUT).build();
        final PublicKeyCredentialCreationOptions options = rp.startRegistration(startRegistrationOptions);
        final WebAuthnCredentialCreationInfo info = new WebAuthnCredentialCreationInfo();
        info.setUsername(username);
        info.setRealm(realm);
        info.setOptions(options);
        info.setProviderId(provider);
        final String key = UUID.randomUUID().toString();
        activeRegistrations.put(key, info);
        final WebAuthnRegistrationResponse response = new WebAuthnRegistrationResponse();
        response.setKey(key);
        response.setOptions(options);
        return response;
    }

    /**
     * Returns:
     * - the username of the authenticated user on successful authentication
     * - null if the authentication was not successful
     * - throws a WebAuthnAuthenticationException if some other error occourred
     */
    public String finishRegistration(
            PublicKeyCredential<AuthenticatorAttestationResponse, ClientRegistrationExtensionOutputs> pkc,
            String realm,
            String key) throws WebAuthnAuthenticationException {
        try {
            final WebAuthnCredentialCreationInfo info = activeRegistrations.get(key);
            if (info == null || info.getRealm() != realm) {
                throw new WebAuthnAuthenticationException("_",
                        "Can not find matching active registration request");
            }
            final String username = info.getUsername();
            if (!StringUtils.hasText(username)) {
                throw new WebAuthnAuthenticationException("_",
                        "Could not finish registration: missing username");
            }
            final WebAuthnUserAccount account = webAuthnUserAccountService.findByProviderAndUsername(provider,
                    username);
            if (account == null) {
                throw new WebAuthnAuthenticationException("_",
                        "Can not find matching account");
            }
            final PublicKeyCredentialCreationOptions options = info.getOptions();
            RegistrationResult result = rp
                    .finishRegistration(FinishRegistrationOptions.builder().request(options).response(pkc).build());
            boolean attestationIsTrusted = result.isAttestationTrusted();
            if (attestationIsTrusted) {
                final WebAuthnCredential newCred = new WebAuthnCredential();
                newCred.setCreatedOn(new Date());
                newCred.setLastUsedOn(new Date());
                newCred.setCredentialId(result.getKeyId().getId().getBase64());
                newCred.setPublicKeyCose(result.getPublicKeyCose().getBase64());
                newCred.setSignatureCount(result.getSignatureCount());
                final Optional<SortedSet<AuthenticatorTransport>> transportsOpt = result.getKeyId().getTransports();
                if (transportsOpt.isPresent()) {
                    newCred.setTransports(
                            convertTransportsToString(transportsOpt.get()));
                } else {
                    newCred.setTransports("");
                }
                newCred.setUserHandle(account.getUserHandle());

                webAuthnCredentialsRepository.save(newCred);
                activeRegistrations.remove(key);
                return username;
            } else {
                return null;
            }
        } catch (Exception e) {
            throw new WebAuthnAuthenticationException("_",
                    "Registration failed");
        }
    }

    private String convertTransportsToString(Set<AuthenticatorTransport> transports) {
        final List<String> result = new LinkedList<>();
        for (final AuthenticatorTransport t : transports) {
            result.add(t.getId());
        }
        return StringUtils.collectionToCommaDelimitedString(result);
    }

    public WebAuthnLoginResponse startLogin(String username) {
        WebAuthnUserAccount account = webAuthnUserAccountService.findByProviderAndUsername(provider, username);
        StartAssertionOptions startAssertionOptions = StartAssertionOptions.builder()
                .userHandle(ByteArray.fromBase64(account.getUserHandle())).timeout(TIMEOUT)
                .userVerification(UserVerificationRequirement.REQUIRED).username(username).build();
        AssertionRequest startAssertion = rp.startAssertion(startAssertionOptions);
        final String key = UUID.randomUUID().toString();
        activeAuthentications.put(key, startAssertion);
        final WebAuthnLoginResponse response = new WebAuthnLoginResponse();
        response.setAssertionRequest(startAssertion);
        response.setKey(key);
        return response;
    }

    // public AssertionRequest startLoginUsernameless(String sessionId) {
    // StartAssertionOptions startAssertionOptions =
    // StartAssertionOptions.builder().timeout(TIMEOUT)
    // .userVerification(UserVerificationRequirement.REQUIRED).build();
    // AssertionRequest startAssertion = rp.startAssertion(startAssertionOptions);
    // activeAuthentications.put(sessionId, startAssertion);
    // return startAssertion;
    // }

    /**
     * @return the authenticated username if authentication was successful, else
     *         null
     */
    public String finishLogin(
            PublicKeyCredential<AuthenticatorAssertionResponse, ClientAssertionExtensionOutputs> pkc,
            String sessionId) throws WebAuthnAuthenticationException {
        try {
            AssertionRequest assertionRequest = activeAuthentications.get(sessionId);
            AssertionResult result = rp.finishAssertion(FinishAssertionOptions.builder().request(assertionRequest)
                    .response(pkc).build());
            if (result.isSuccess() && result.isSignatureCounterValid()) {
                final WebAuthnUserAccount account = webAuthnUserAccountService
                        .findByUserHandle(result.getUserHandle().getBase64());
                List<WebAuthnCredential> credentials = webAuthnCredentialsRepository
                        .findByUserHandle(account.getUserHandle());
                Optional<WebAuthnCredential> toUpdate = Optional.empty();
                ByteArray resultCredentialId = result.getCredentialId();
                for (WebAuthnCredential c : credentials) {
                    ByteArray cCredentialId = ByteArray.fromBase64(c.getCredentialId());
                    if (cCredentialId.equals(resultCredentialId)) {
                        toUpdate = Optional.of(c);
                    }
                }
                if (toUpdate.isEmpty()) {
                    throw new WebAuthnAuthenticationException(account.getSubject(),
                            "Could not find the requested credential in the account");
                }
                WebAuthnCredential credential = toUpdate.get();
                credentials.remove(credential);
                credential.setSignatureCount(result.getSignatureCount());
                credential.setLastUsedOn(new Date());
                webAuthnCredentialsRepository.save(credential);
                final String username = account.getUsername();
                if (!StringUtils.hasText(username)) {
                    throw new WebAuthnAuthenticationException(account.getSubject(),
                            "Could not find the requested credential in the account");
                }
                return username;
            } else {
                return null;
            }
        } catch (Exception e) {
            throw new WebAuthnAuthenticationException("_",
                    "Login failed");
        }
    }

    UserIdentity generateUserIdentity(String username, String realm, String displayName,
            Subject subjectOrNull) {
        final String userDisplayName = displayName != null ? displayName : "";
        byte[] userHandle = new byte[64];
        random.nextBytes(userHandle);
        final ByteArray userHandleBA = new ByteArray(userHandle);
        final UserIdentity newUserIdentity = UserIdentity.builder()
                .name(username).displayName(userDisplayName)
                .id(userHandleBA).build();
        WebAuthnUserAccount account = new WebAuthnUserAccount();
        account.setUsername(username);
        account.setRealm(realm);
        String subject;
        if (subjectOrNull != null) {
            subject = subjectOrNull.getSubjectId();
        } else {
            subject = subjectService.generateUuid(SystemKeys.RESOURCE_USER);
        }
        account.setSubject(subject);
        account.setUserHandle(userHandleBA.getBase64());
        account.setProvider(provider);
        webAuthnUserAccountService.addAccount(account);
        return newUserIdentity;
    }
}
