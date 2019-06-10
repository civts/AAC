
package it.smartcommunitylab.aac.openid.service;

import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.common.OAuth2AccessToken;
import org.springframework.security.oauth2.provider.OAuth2Authentication;
import org.springframework.security.oauth2.provider.OAuth2Request;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.nimbusds.jose.Algorithm;
import com.nimbusds.jose.JWEHeader;
import com.nimbusds.jose.JWEObject;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.EncryptedJWT;
import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.JWTClaimsSet.Builder;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;

import it.smartcommunitylab.aac.dto.BasicProfile;
import it.smartcommunitylab.aac.manager.BasicProfileManager;
import it.smartcommunitylab.aac.manager.RoleManager;
import it.smartcommunitylab.aac.manager.UserManager;
import it.smartcommunitylab.aac.model.ClientDetailsEntity;
import it.smartcommunitylab.aac.model.Role;
import it.smartcommunitylab.aac.model.User;
import it.smartcommunitylab.aac.openid.view.UserInfoView;
import it.smartcommunitylab.aac.repository.ClientDetailsRepository;
/**
 * Default implementation of service to create specialty OpenID Connect tokens.
 *
 * @author Amanda Anganes
 *
 */
@Service
public class OIDCTokenEnhancer  {

	public String MAX_AGE = "max_age";
	public String NONCE = "nonce";
	public static final String AUTH_TIMESTAMP = "AUTH_TIMESTAMP";

	@Value("${openid.issuer}")
	private String issuer;

	
	/**
	 * Logger for this class
	 */
	private static final Logger logger = LoggerFactory.getLogger(OIDCTokenEnhancer.class);

	@Autowired
	private JWTSigningAndValidationService jwtService;


	@Autowired
	private ClientKeyCacheService encrypters;

	@Autowired
	private SymmetricKeyJWTValidatorCacheService symmetricCacheService;
	@Autowired
	private ClientDetailsRepository clientRepository;
	
    @Autowired
    private UserManager userManager;

    @Autowired
    private BasicProfileManager profileManager;

    @Autowired
    private RoleManager roleManager;
    
	public JWT createIdToken(OAuth2AccessToken accessToken, OAuth2Authentication authentication) {
		OAuth2Request request = authentication.getOAuth2Request();
		String clientId = request.getClientId();
		
		logger.debug("fetch user via authentication");
//		User user = userManager.getUser();
		User user = null;
        try {
            // fetch from auth
            Object principal = authentication.getPrincipal();
            org.springframework.security.core.userdetails.User auth = (org.springframework.security.core.userdetails.User) principal;
            logger.debug("principal username " + auth.getUsername());

            //fetch user from db
            long userId = Long.parseLong(auth.getUsername());
            user = userManager.findOne(userId);
            
        } catch (Exception e) {
           // user is not available, thus all user claims will fail
        }
		ClientDetailsEntity client = clientRepository.findByClientId(clientId);
		
		SignedJWT signed = createJWT(client, accessToken, authentication);
		
		JWSAlgorithm signingAlg = jwtService.getDefaultSigningAlgorithm();

		if (ClientKeyCacheService.getUserInfoSignedResponseAlg(client) != null) {
			signingAlg = ClientKeyCacheService.getUserInfoSignedResponseAlg(client);
		}


		JWT idToken = null;

		JWTClaimsSet.Builder idClaims = new JWTClaimsSet.Builder();

		// if the auth time claim was explicitly requested OR if the client always wants the auth time, put it in
		if (request.getExtensions().containsKey(MAX_AGE) || (request.getExtensions().containsKey("idtoken"))) {

			if (request.getExtensions().get(AUTH_TIMESTAMP) != null) {

				Long authTimestamp = Long.parseLong((String) request.getExtensions().get(AUTH_TIMESTAMP));
				if (authTimestamp != null) {
					idClaims.claim("auth_time", authTimestamp / 1000L);
				}
			} else {
				// we couldn't find the timestamp!
				logger.warn("Unable to find authentication timestamp! There is likely something wrong with the configuration.");
			}
		}

		idClaims.issueTime(new Date());

		if (accessToken.getExpiration() != null) {
			Date expiration = accessToken.getExpiration();
			idClaims.expirationTime(expiration);
		}

		idClaims.issuer(issuer);
		idClaims.subject(authentication.getName());
		idClaims.audience(Lists.newArrayList(client.getClientId()));
		idClaims.jwtID(UUID.randomUUID().toString()); // set a random NONCE in the middle of it

		String nonce = (String)request.getExtensions().get(NONCE);
		if (!Strings.isNullOrEmpty(nonce)) {
			idClaims.claim("nonce", nonce);
		}

		//add additional claims for scopes
		idClaims.claim("scope", String.join(" ", request.getScope()));
		
		//add claims for user details if requested via scopes
		if(user != null) {
		     logger.debug("fetch profile via profilemanager");

            BasicProfile profile = profileManager.getBasicProfileById(user.getId().toString());
            logger.debug("profile dump "+profile.toString());

    		//when requesting openId scope we implicitly require basic profile
		    idClaims.claim("username", profile.getUsername());
	        idClaims.claim("preferred_username", profile.getUsername());
    
   	         if(request.getScope().contains("profile")) {
                idClaims.claim("given_name", profile.getName());
                idClaims.claim("family_name", profile.getSurname());
                idClaims.claim("name", profile.getSurname() + " " + profile.getName());    	        
    		}
    		
    		if(request.getScope().contains("email")) {
                idClaims.claim("email", profile.getUsername());
    		}
    		
            if (request.getScope().contains("user.roles.me")) {
                try {
                    Set<Role> roles = roleManager.getRoles(user.getId());
                    if (roles != null) {
                        
                        // build roles list as plain array
                        Set<String> rolesList = new HashSet<>();
                        for (Role role : roles) {
                            rolesList.add(role.getAuthority());
                        }
                        idClaims.claim("roles", rolesList.toArray(new String[0]));

                        // build also as realm/resource claims
                        Set<String> realmRoles = new HashSet<>();
                        for (Role role : roles) {
                            if(StringUtils.isEmpty(role.getContext()) && StringUtils.isEmpty(role.getSpace())) {
                                realmRoles.add(role.getRole());
                            }
                        }
                        net.minidev.json.JSONObject realmRoleObj = new net.minidev.json.JSONObject();
                        realmRoleObj.put("roles", realmRoles.toArray(new String[0]));
                        idClaims.claim("realm_access", realmRoleObj);
                        
                        Map<String,Set<String>> resourceRoles = new HashMap<>();
                        for (Role role : roles) {
                            // role is context/space:role 
                            if(!StringUtils.isEmpty(role.getContext()) && !StringUtils.isEmpty(role.getSpace())) {
                                // put as context/space:role 
                                String key = role.getContext()+ "/"+role.getSpace();
                                if(!resourceRoles.containsKey(key)) {
                                    resourceRoles.put(key, new HashSet<String>());
                                }                               
                                resourceRoles.get(key).add(role.getRole());                                
                            } else if(!StringUtils.isEmpty(role.getContext()) && StringUtils.isEmpty(role.getSpace())) { 
                                //put as context:role
                                if(!resourceRoles.containsKey(role.getContext())) {
                                    resourceRoles.put(role.getContext(), new HashSet<String>());
                                }                                
                                resourceRoles.get(role.getContext()).add(role.getRole());                                
                            }                                                       
                        }
                        
                        net.minidev.json.JSONObject resourceRolesObj = new net.minidev.json.JSONObject();
                        for(String res : resourceRoles.keySet()) {
                            net.minidev.json.JSONObject resObj = new net.minidev.json.JSONObject();                                                      
                            resObj.put("roles", resourceRoles.get(res).toArray(new String[0]));
                            resourceRolesObj.put(res, resObj);
                        }                        
                        idClaims.claim("resource_access", resourceRolesObj);
                        
                        
                        // also build list of "groups" (as plain array)
                        // define a group as context+space, ignoring role
                        Set<String> groups = new HashSet<>();
                        for (Role role : roles) {
                            if (!StringUtils.isEmpty(role.getContext()) && !StringUtils.isEmpty(role.getSpace())) {
                                groups.add(role.getContext() + "/" + role.getSpace());
                            }
                        }
                        idClaims.claim("groups", groups.toArray(new String[0]));                   
                    }
                } catch (Exception rex) {
                    logger.error("error fetching roles for user " + user.getId());
                }
            }
        }
		
		Set<String> responseTypes = request.getResponseTypes();

		if (responseTypes.contains("token")) {
			// calculate the token hash
			Base64URL at_hash = IdTokenHashUtils.getAccessTokenHash(signingAlg, signed);
			idClaims.claim("at_hash", at_hash);
		}

		if (ClientKeyCacheService.getUserInfoEncryptedResponseAlg(client) != null && !ClientKeyCacheService.getUserInfoEncryptedResponseAlg(client).equals(Algorithm.NONE)
				&& ClientKeyCacheService.getUserInfoEncryptedResponseEnc(client) != null && !ClientKeyCacheService.getUserInfoEncryptedResponseEnc(client).equals(Algorithm.NONE)
				&& (!Strings.isNullOrEmpty(ClientKeyCacheService.getJwksUri(client)) || ClientKeyCacheService.getJwks(client) != null)) {

			JWTEncryptionAndDecryptionService encrypter = encrypters.getEncrypter(client);

			if (encrypter != null) {

				idToken = new EncryptedJWT(new JWEHeader(ClientKeyCacheService.getUserInfoEncryptedResponseAlg(client), ClientKeyCacheService.getUserInfoEncryptedResponseEnc(client)), idClaims.build());

				encrypter.encryptJwt((JWEObject) idToken);

			} else {
				logger.error("Couldn't find encrypter for client: " + client.getClientId());
			}

		} else {

			if (signingAlg.equals(Algorithm.NONE)) {
				// unsigned ID token
				idToken = new PlainJWT(idClaims.build());

			} else {

				// signed ID token

				if (signingAlg.equals(JWSAlgorithm.HS256)
						|| signingAlg.equals(JWSAlgorithm.HS384)
						|| signingAlg.equals(JWSAlgorithm.HS512)) {

					JWSHeader header = new JWSHeader(signingAlg, null, null, null, null, null, null, null, null, null,
							jwtService.getDefaultSignerKeyId(),
							null, null);
					idToken = new SignedJWT(header, idClaims.build());

					JWTSigningAndValidationService signer = symmetricCacheService.getSymmetricValidtor(client);

					// sign it with the client's secret
					signer.signJwt((SignedJWT) idToken);
				} else {
					idClaims.claim("kid", jwtService.getDefaultSignerKeyId());

					JWSHeader header = new JWSHeader(signingAlg, null, null, null, null, null, null, null, null, null,
							jwtService.getDefaultSignerKeyId(),
							null, null);

					idToken = new SignedJWT(header, idClaims.build());

					// sign it with the server's key
					jwtService.signJwt((SignedJWT) idToken);
				}
			}

		}

		return idToken;
	}

	private SignedJWT createJWT(ClientDetailsEntity client, OAuth2AccessToken accessToken, OAuth2Authentication authentication) {
		OAuth2Request originalAuthRequest = authentication.getOAuth2Request();

		String clientId = originalAuthRequest.getClientId();

		Builder builder = new JWTClaimsSet.Builder()
				.claim("azp", clientId)
				.issuer(issuer)
				.issueTime(new Date())
				.expirationTime(accessToken.getExpiration())
				.subject(authentication.getName())
				.jwtID(UUID.randomUUID().toString()); // set a random NONCE in the middle of it

		String audience = (String) originalAuthRequest.getExtensions().get("aud");
		if (!Strings.isNullOrEmpty(audience)) {
			builder.audience(Lists.newArrayList(audience));
		}

		JWTClaimsSet claims = builder.build();

		JWSAlgorithm signingAlg = jwtService.getDefaultSigningAlgorithm();
		JWSHeader header = new JWSHeader(signingAlg, null, null, null, null, null, null, null, null, null,
				jwtService.getDefaultSignerKeyId(),
				null, null);
		SignedJWT signed = new SignedJWT(header, claims);

		jwtService.signJwt(signed);
		return signed;
	}	


}