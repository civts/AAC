/**
 *    Copyright 2012-2013 Trento RISE
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
 */

package it.smartcommunitylab.aac.manager;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.persistence.EntityNotFoundException;
import javax.transaction.Transactional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import com.google.common.collect.Sets;

import it.smartcommunitylab.aac.Config;
import it.smartcommunitylab.aac.apimanager.model.DataList;
import it.smartcommunitylab.aac.apimanager.model.RoleModel;
import it.smartcommunitylab.aac.apimanager.model.Subscription;
import it.smartcommunitylab.aac.common.Utils;
import it.smartcommunitylab.aac.model.Role;
import it.smartcommunitylab.aac.model.User;
import it.smartcommunitylab.aac.repository.UserRepository;

/**
 * Used to check whether the user has the administrator rights.
 * @author raman
 *
 */
@Component
@Transactional
public class RoleManager {

	@Value("${admin.password}")
	private String adminPassword;	

	@Value("${admin.contexts}")
	private String[] defaultContexts;
	@Value("${admin.contextSpaces}")
	private String[] defaultContextSpaces;
	

	@Autowired
	private RegistrationService registrationService;
	
	@Autowired
	private UserRepository userRepository;	
	
	
	public User init() {
		Set<Role> roles = new HashSet<>();
		Role role = Role.systemAdmin();
		roles.add(role);
		if (defaultContexts != null) {
		    Arrays.asList(defaultContexts).forEach(ctx -> roles.add(Role.ownerOf(ctx)));
		}
		if (defaultContextSpaces != null) {
			Arrays.asList(defaultContextSpaces).forEach(ctx -> roles.add(Role.ownerOf(ctx)));
		}
		User admin = null;
		admin = userRepository.findByUsername("admin");
		if (admin == null) {
			admin = registrationService.registerOffline("admin", "admin", "admin", adminPassword, null, false, null);
		}
		admin.getRoles().addAll(roles);
		userRepository.saveAndFlush(admin);
		return admin;
	}
	
	
	public void updateRoles(User user, Set<Role> rolesToAdd, Set<Role> rolesToDelete) {
		Set<Role> roles = user.getRoles();
		roles.removeAll(rolesToDelete);
		roles.addAll(rolesToAdd);
		user.setRoles(roles);
		userRepository.saveAndFlush(user);
	}
	
	public void addRole(User user, Role role) {
//		Set<Role> roles = Sets.newHashSet(user.getRoles());
		if (user.getRoles() == null) {
			user.setRoles(new HashSet<>());
		}
		user.getRoles().add(role);
		userRepository.saveAndFlush(user);
	}
	
	public void removeRole(User user, Role role) {
		Set<Role> roles = Sets.newHashSet(user.getRoles());
		roles.remove(role);
		
		user.setRoles(roles);
		userRepository.saveAndFlush(user);
	}	
	
	public Set<Role> getRoles(User user) {
		return user.getRoles();
	}		
	
	public boolean hasRole(User user, Role role) {
		return user.getRoles().contains(role);
	}
	
	/**
	 * List all users where role matches the role value, context, and space
	 * @param role
	 * @param context
	 * @param space
	 * @param page
	 * @param pageSize
	 * @return
	 */
	public List<User> findUsersByRole(String role, String context, String space, int page, int pageSize) {
		Pageable pageable = new PageRequest(page, pageSize);
		return userRepository.findByFullRole(role, context, space, pageable);
	}
	/**
	 * List all users where role matches the role value and context (arbitrary space)
	 * @param role
	 * @param context
	 * @param page
	 * @param pageSize
	 * @return
	 */
	public List<User> findUsersByRole(String role, String context, int page, int pageSize) {
		Pageable pageable = new PageRequest(page, pageSize);
		return userRepository.findByRole(role, context, pageable);
	}

	/**
	 * List all users where role matches context and space (arbitrary role value)
	 * @param context
	 * @param space
	 * @param page
	 * @param pageSize
	 * @return
	 */
	public List<User> findUsersByContext(String context, String space, int page, int pageSize) {
		Pageable pageable = new PageRequest(page, pageSize);
		return userRepository.findByRoleContext(context, space, pageable);
	}
	
	public List<GrantedAuthority> buildAuthorities(User user) {
		Set<Role> roles = getRoles(user);
		roles.add(Role.systemUser());
		List<GrantedAuthority> list = roles.stream().collect(Collectors.toList());
		return list;
	}
	
	/**
	 * Update subscriptions - set roles from the specified context/space
	 * @param subs
	 * @param context
	 * @param space
	 */
	public void fillRoles(DataList<Subscription> subs, String context, String space) {
		for (Subscription sub: subs.getList()) {
			String subscriber = sub.getSubscriber();
			String info[] = Utils.extractInfoFromTenant(subscriber);
			final String name = info[0];
			
			User user = userRepository.findByUsername(name);
			
			Set<Role> userRoles = user.spaceRole(context, space);
			List<String> roleNames = userRoles.stream().map(r -> r.getRole()).collect(Collectors.toList());
			sub.setRoles(roleNames);
		}
	}	
	
	/**
	 * Update user roles at the specified context/space according to the specified model add/delete.
	 * @param roleModel
	 * @param context
	 * @param space
	 * @return
	 */
	public List<String> updateLocalRoles(RoleModel roleModel, String context, String space) {
		String info[] = Utils.extractInfoFromTenant(roleModel.getUser());
		
		final String name = info[0];
		
		User user = userRepository.findByUsername(name);
		if (user == null) throw new EntityNotFoundException("User "+name + " does not exist");
		
		Set<Role> userRoles = new HashSet<>(user.getRoles());

		if (roleModel.getRemoveRoles() != null) {
			for (String role : roleModel.getRemoveRoles()) {
				Role r = new Role(context, space, role);
				userRoles.remove(r);
			}
		}
		if (roleModel.getAddRoles() != null) {
			for (String role : roleModel.getAddRoles()) {
				Role r = new Role(context, space, role);
				userRoles.add(r);
			}
		}
		user.getRoles().clear();
		user.getRoles().addAll(userRoles);

		userRepository.save(user);
		
		return user.spaceRole(context, space).stream().map(r -> r.getRole()).collect(Collectors.toList());
	}	

	/**
	 * Update sub-space owners for the specified context/space according to the specified model add/delete.
	 * @param roleModel
	 * @param context
	 * @param space
	 * @return
	 */
	public List<String> updateLocalOwners(RoleModel roleModel, String context, String space) {
		String info[] = Utils.extractInfoFromTenant(roleModel.getUser());
		
		final String name = info[0];
		
		Role parent = new Role(context, space, Config.R_PROVIDER);
		String parentContext = parent.canonicalSpace();
		
		User user = userRepository.findByUsername(name);
		if (user == null) throw new EntityNotFoundException("User "+name + " does not exist");
		
		Set<Role> userRoles = new HashSet<>(user.getRoles());

		if (roleModel.getRemoveRoles() != null) {
			for (String child : roleModel.getRemoveRoles()) {
				Role r = new Role(parentContext, child, Config.R_PROVIDER);
				userRoles.remove(r);
			}
		}
		if (roleModel.getAddRoles() != null) {
			for (String child : roleModel.getAddRoles()) {
				Role r = new Role(parentContext, child, Config.R_PROVIDER);
				userRoles.add(r);
			}
		}
		user.getRoles().clear();
		user.getRoles().addAll(userRoles);

		userRepository.save(user);
		
		return user.contextRole(Config.R_PROVIDER, parentContext).stream().map(r -> r.getSpace()).collect(Collectors.toList());
	}	
}
