package it.smartcommunitylab.aac.model;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.springframework.util.Assert;

import it.smartcommunitylab.aac.core.auth.RealmGrantedAuthority;
import it.smartcommunitylab.aac.core.model.UserAttributes;
import it.smartcommunitylab.aac.core.model.UserIdentity;

/*
 * A model describing the user outside the auth/security context.
 * 
 * Can be safely used to manage attributes/properties, and also roles both in 
 * same-realm and cross-realm scenarios.
 * 
 * Do note that in cross realm managers and builders should properly handle private attributes and 
 * disclose only appropriate identities/properties. 
 */
public class User {

    // base attributes
    // always set
    private final String subjectId;
    
    // describes the realm responsible for this user
    private final String source;

    // realm describes the current user for the given realm
    private String realm;

    // basic profile
    // could be empty
    private String name;
    private String surname;
    private String username;
    private String email;

    // identities associated with this user
    // this will be populated as needed, make no assumption about being always set
    // or complete: for example the only identity provided could be the one
    // selected for the authentication request, or those managed by a given
    // authority etc
    // these could also be empty in cross-realm scenarios
    private Set<UserIdentity> identities;

    // realm roles (ie authorities in AAC)
    // these can be managed inside realms
    // do note that the set should describe only the roles for the current context
    private Set<RealmGrantedAuthority> authorities;

    // roles are OUTSIDE aac (ie not grantedAuthorities)
    // roles are associated to USER(=subjectId) not single identities/realms
    // this field should be used for caching, consumers should refresh
    // otherwise we should implement an (external) expiring + refreshing cache with
    // locking.
    // this field is always discosed in cross-realm scenarios
    private Set<SpaceRole> roles;

    // additional attributes as UserAttributes collection
    // these attributes should be kept consistent with the context(ie realm)
    private Set<UserAttributes> attributes;

    public User(String subjectId, String source) {
        Assert.hasText(subjectId, "subject can not be null or empty");
        Assert.notNull(source, "source realm can not be null");

        this.subjectId = subjectId;
        this.source = source;
        // set consuming realm to source
        this.realm = source;
        this.authorities = Collections.emptySet();
        this.identities = new HashSet<>();
        this.attributes = new HashSet<>();
        this.roles = new HashSet<>();
    }

    public String getSubjectId() {
        return subjectId;
    }

    public String getSource() {
        return source;
    }

    public Set<RealmGrantedAuthority> getAuthorities() {
        return authorities;
    }

    public void setAuthorities(Collection<RealmGrantedAuthority> authorities) {
        this.authorities = new HashSet<>();
        this.authorities.addAll(authorities);
    }

    public String getRealm() {
        return realm;
    }

    public void setRealm(String realm) {
        this.realm = realm;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSurname() {
        return surname;
    }

    public void setSurname(String surname) {
        this.surname = surname;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public Set<UserIdentity> getIdentities() {
        return identities;
    }

    public void setIdentities(Collection<UserIdentity> identities) {
        this.identities = new HashSet<>();
        this.identities.addAll(identities);
    }

    public void addIdentity(UserIdentity identity) {
        identities.add(identity);
    }

    public Set<UserAttributes> getAttributes() {
        return attributes;
    }

    public void setAttributes(Collection<UserAttributes> attributes) {
        this.attributes = new HashSet<>();
        this.attributes.addAll(attributes);
    }

    public void addAttributes(UserAttributes attributes) {
        this.attributes.add(attributes);
    }

    /*
     * Authorities (realm)
     * 
     */

    /*
     * Roles are mutable and comparable
     */

    public Set<SpaceRole> getRoles() {
        return roles;
    }

    public void setRoles(Collection<SpaceRole> rr) {
        this.roles = new HashSet<>();
        addRoles(rr);
    }

    public void addRoles(Collection<SpaceRole> rr) {
        roles.addAll(rr);
    }

    public void removeRoles(Collection<SpaceRole> rr) {
        roles.removeAll(rr);
    }

    public void addRole(SpaceRole r) {
        this.roles.add(r);
    }

    public void removeRole(SpaceRole r) {
        this.roles.remove(r);
    }

}
