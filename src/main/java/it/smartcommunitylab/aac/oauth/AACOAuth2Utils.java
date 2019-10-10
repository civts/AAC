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

package it.smartcommunitylab.aac.oauth;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.apache.commons.codec.binary.Base64;
import org.springframework.security.crypto.codec.Utf8;

/**
 * @author raman
 *
 */
public class AACOAuth2Utils {
	public static final String CODE_CHALLENGE = "code_challenge";
	public static final String CODE_VERIFIER = "code_verifier";
	public static final String CODE_CHALLENGE_METHOD = "code_challenge_method";

	/**
    * Generates the code challenge from a given code verifier and code challenge method.
    * @param codeVerifier
    * @param codeChallengeMethod allowed values are only <code>plain</code> and <code>S256</code>
    * @return
    */
    public static String getCodeChallenge(String codeVerifier, String codeChallengeMethod) {
    	if (codeChallengeMethod.equals("plain")) {
    		return codeVerifier;
    	}
    	else if (codeChallengeMethod.equalsIgnoreCase("S256")) {
    		return getS256CodeChallenge(codeVerifier);
    	}
    	else {
    		throw new IllegalArgumentException(codeChallengeMethod + " is not a supported code challenge method.");
    	}
    }
	
    private static String getS256CodeChallenge(String codeVerifier) {
    	MessageDigest md;
    	try {
    		md = MessageDigest.getInstance("SHA-256");
    	}
    	catch (NoSuchAlgorithmException e) {
    		throw new IllegalArgumentException("No such algorithm [SHA-256]");
    	}
    	byte[] sha256 = md.digest(Utf8.encode(codeVerifier));
    	String codeChallenge = Base64.encodeBase64URLSafeString(sha256);
    	return codeChallenge;
    }

}
