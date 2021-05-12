/*******************************************************************************
 * Copyright 2015 Fondazione Bruno Kessler
 * 
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 * 
 *        http://www.apache.org/licenses/LICENSE-2.0
 * 
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 ******************************************************************************/

package it.smartcommunitylab.aac.controller;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import javax.validation.Valid;
import javax.validation.constraints.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;

import it.smartcommunitylab.aac.SystemKeys;
import it.smartcommunitylab.aac.common.InvalidDefinitionException;
import it.smartcommunitylab.aac.common.NoSuchProviderException;
import it.smartcommunitylab.aac.common.NoSuchRealmException;
import it.smartcommunitylab.aac.common.NoSuchUserException;
import it.smartcommunitylab.aac.core.AuthenticationHelper;
import it.smartcommunitylab.aac.core.ProviderManager;
import it.smartcommunitylab.aac.core.UserDetails;
import it.smartcommunitylab.aac.core.UserManager;
import it.smartcommunitylab.aac.core.model.UserAccount;
import it.smartcommunitylab.aac.core.model.UserAttributes;
import it.smartcommunitylab.aac.core.model.UserIdentity;
import it.smartcommunitylab.aac.core.provider.CredentialsService;
import it.smartcommunitylab.aac.core.provider.IdentityService;
import it.smartcommunitylab.aac.dto.ConnectedAppProfile;
import it.smartcommunitylab.aac.dto.UserProfile;
import it.smartcommunitylab.aac.model.SpaceRole;
import it.smartcommunitylab.aac.profiles.ProfileManager;
import it.smartcommunitylab.aac.profiles.model.AccountProfile;
import it.smartcommunitylab.aac.profiles.model.BasicProfile;

/**
 * Application controller for user UI: account
 * 
 * Should handle only "current" user operations
 * 
 * @author raman
 *
 */
@Controller
public class UserAccountController {

    @Autowired
    private AuthenticationHelper authHelper;

    @Autowired
    private UserManager userManager;

//    @Autowired
//    private InternalUserManager internalUserManager;

    @Autowired
    private ProfileManager profileManager;

//    @Autowired
//    private InternalUserAccountService userAccountService;

    @Autowired
    private ProviderManager providerManager;

    // TODO MANAGE accounts: add/merge, delete

    @RequestMapping("/")
    public ModelAndView home() {
        return new ModelAndView("redirect:/account");
    }

    @RequestMapping("/account")
    public ModelAndView account() {
        UserDetails user = authHelper.getUserDetails();

        Map<String, Object> model = new HashMap<String, Object>();
        String username = user.getUsername();
        model.put("username", username);
        return new ModelAndView("account", model);
    }

    @GetMapping("/account/profile")
    public ResponseEntity<UserDetails> myProfile() throws InvalidDefinitionException {
        UserDetails user = authHelper.getUserDetails();
        if (user == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(user);

    }
    @GetMapping("/account/profile/roles")
    public ResponseEntity<Collection<SpaceRole>> mySpaceRoles() throws InvalidDefinitionException, NoSuchUserException, NoSuchRealmException {
        return ResponseEntity.ok(userManager.getMyRoles());

    }

    @GetMapping("/account/accounts")
    public ResponseEntity<Collection<AccountProfile>> getAccounts() throws InvalidDefinitionException {
        UserDetails user = authHelper.getUserDetails();
        if (user == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        Collection<AccountProfile> profiles = profileManager.curAccountProfiles();

        return ResponseEntity.ok(profiles);
    }

    @DeleteMapping("/account/profile")
    public ResponseEntity<Void> deleteProfile() throws NoSuchUserException, NoSuchRealmException {
        UserDetails user = authHelper.getUserDetails();
        if (user == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        userManager.removeUser(user.getRealm(), user.getSubjectId());
        return ResponseEntity.ok().build();
    }

//    @PostMapping("/account/profile")
//    public ResponseEntity<BasicProfile> updateProfile(@RequestBody UserProfile profile)
//            throws InvalidDefinitionException {
//        UserDetails user = authHelper.getUserDetails();
//        if (user == null) {
//            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
//        }
//        try {
//            // TODO use update, the current user must exists
//            // TODO implement update in userManager
////            internalUserManager.updateOrCreateAccount(cur.getSubjectId(), cur.getRealm(), profile.getUsername(), profile.getPassword(), profile.getEmail(), profile.getName(), profile.getSurname(), profile.getLang(), Collections.emptySet());
//
//        } catch (Exception e) {
//            return ResponseEntity.badRequest().build();
//        }
//        return ResponseEntity.ok(profileManager.curBasicProfile());
//    }

    @GetMapping("/account/attributes")
    public ResponseEntity<Collection<UserAttributes>> readAttributes() {
        Collection<UserAttributes> result = userManager.getMyAttributes();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/account/connections")
    public ResponseEntity<Collection<ConnectedAppProfile>> readConnectedApps() {
        Collection<ConnectedAppProfile> result = userManager.getMyConnectedApps();
        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/account/connections/{clientId}")
    public ResponseEntity<Collection<ConnectedAppProfile>> deleteConnectedApp(@PathVariable String clientId) {

        userManager.deleteMyConnectedApp(clientId);

        Collection<ConnectedAppProfile> result = userManager.getMyConnectedApps();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/credentials/{userId}")
    public ModelAndView credentials(
            @PathVariable @Valid @Pattern(regexp = SystemKeys.SLUG_PATTERN) String userId)
            throws NoSuchProviderException, NoSuchUserException {
        // first check userid vs user
        UserDetails user = authHelper.getUserDetails();
        UserIdentity identity = user.getIdentity(userId);

        if (identity == null) {
            throw new IllegalArgumentException("userid invalid");
        }

        UserAccount account = identity.getAccount();

        // fetch provider
        String providerId = identity.getProvider();
        IdentityService idp = providerManager.getIdentityService(providerId);

        // fetch credentials service if available
        CredentialsService service = idp.getCredentialsService();

        if (service == null) {
            throw new IllegalArgumentException("credentials are immutable");
        }

        if (!service.canSet()) {
            throw new IllegalArgumentException("credentials are immutable");
        }

        String url = service.getSetUrl();
        return new ModelAndView("redirect:" + url);
    }

}
