/* Licence.java
 * Component: ProperJavaRDP
 *
 * Copyright (c) 2005 Propero Limited
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or (at
 * your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307
 * USA
 *
 * (See gpl.txt for details of the GNU General Public License.)
 *
 */
// Created on 02-Jul-2003
package net.propero.rdp;

import java.io.IOException;
import java.io.UnsupportedEncodingException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.crypto.engines.RC4Engine;
import org.bouncycastle.crypto.params.KeyParameter;

/**
 * Handles request, receipt and processing of licences
 */
public class Licence {
	private Secure secure = null;

	Licence(Options options, Secure s) {
		secure = s;
		licence_key = new byte[16];
		licence_sign_key = new byte[16];
		this.options = options;
	}

	private byte[] licence_key = null;

	private byte[] licence_sign_key = null;

	private byte[] in_token = null, in_sig = null;

	private static final Logger LOGGER = LogManager.getLogger();

	/* constants for the licence negotiation */
	private static final int LICENCE_TOKEN_SIZE = 10;

	private static final int LICENCE_HWID_SIZE = 20;

	private static final int LICENCE_SIGNATURE_SIZE = 16;

	/*
	 * private static final int LICENCE_TAG_DEMAND = 0x0201; private static
	 * final int LICENCE_TAG_AUTHREQ = 0x0202; private static final int
	 * LICENCE_TAG_ISSUE = 0x0203; private static final int LICENCE_TAG_REISSUE =
	 * 0x0204; // rdesktop 1.2.0 private static final int LICENCE_TAG_PRESENT =
	 * 0x0212; // rdesktop 1.2.0 private static final int LICENCE_TAG_REQUEST =
	 * 0x0213; private static final int LICENCE_TAG_AUTHRESP = 0x0215; private
	 * static final int LICENCE_TAG_RESULT = 0x02ff;
	 */

	private static final int LICENCE_TAG_DEMAND = 0x01;

	private static final int LICENCE_TAG_AUTHREQ = 0x02;

	private static final int LICENCE_TAG_ISSUE = 0x03;

	private static final int LICENCE_TAG_REISSUE = 0x04;

	private static final int LICENCE_TAG_PRESENT = 0x12;

	private static final int LICENCE_TAG_REQUEST = 0x13;

	private static final int LICENCE_TAG_AUTHRESP = 0x15;

	private static final int LICENCE_TAG_RESULT = 0xff;

	private static final int LICENCE_TAG_USER = 0x000f;

	private static final int LICENCE_TAG_HOST = 0x0010;

	private final Options options;

	public Licence(Options options) {
		this.options = options;
	}

	public byte[] generate_hwid() throws UnsupportedEncodingException {
		byte[] hwid = new byte[LICENCE_HWID_SIZE];
		secure.setLittleEndian32(hwid, 2);
		byte[] name = options.hostname.getBytes("US-ASCII");

		if (name.length > LICENCE_HWID_SIZE - 4) {
			System.arraycopy(name, 0, hwid, 4, LICENCE_HWID_SIZE - 4);
		} else {
			System.arraycopy(name, 0, hwid, 4, name.length);
		}
		return hwid;
	}

	/**
	 * Process and handle licence data from a packet
	 *
	 * @param data
	 *            Packet containing licence data
	 * @throws RdesktopException
	 * @throws IOException
	 */
	public void process(RdpPacket data) throws RdesktopException,
	IOException {
		int tag = 0;
		tag = data.get8();
		data.incrementPosition(3); // version, length

		switch (tag) {

		case (LICENCE_TAG_DEMAND):
			this.process_demand(data);
		break;

		case (LICENCE_TAG_AUTHREQ):
			this.process_authreq(data);
		break;

		case (LICENCE_TAG_ISSUE):
			this.process_issue(data);
		break;

		case (LICENCE_TAG_REISSUE):
			LOGGER.debug("Presented licence was accepted!");
		break;

		case (LICENCE_TAG_RESULT):
			break;

		default:
			LOGGER.warn("got licence tag: " + tag);
		}

	}

	/**
	 * Process a demand for a licence. Find a license and transmit to server, or
	 * request new licence
	 *
	 * @param data
	 *            Packet containing details of licence demand
	 * @throws UnsupportedEncodingException
	 * @throws RdesktopException
	 * @throws IOException
	 */
	public void process_demand(RdpPacket data)
			throws UnsupportedEncodingException, RdesktopException,
			IOException {
		byte[] null_data = new byte[Secure.SEC_MODULUS_SIZE];
		byte[] server_random = new byte[Secure.SEC_RANDOM_SIZE];
		byte[] host = options.hostname.getBytes("US-ASCII");
		byte[] user = options.username.getBytes("US-ASCII");

		/* retrieve the server random */
		data.copyToByteArray(server_random, 0, data.getPosition(),
				server_random.length);
		data.incrementPosition(server_random.length);

		/* Null client keys are currently used */
		this.generate_keys(null_data, server_random, null_data);

		if (!options.built_in_licence && options.load_licence) {
			byte[] licence_data = load_licence();
			if ((licence_data != null) && (licence_data.length > 0)) {
				LOGGER.debug("licence_data.length = " + licence_data.length);
				/* Generate a signature for the HWID buffer */
				byte[] hwid = generate_hwid();
				byte[] signature = secure.sign(this.licence_sign_key, 16, 16,
						hwid, hwid.length);

				/* now crypt the hwid */
				RC4Engine rc4_licence = new RC4Engine();
				byte[] crypt_key = new byte[this.licence_key.length];
				byte[] crypt_hwid = new byte[LICENCE_HWID_SIZE];
				System.arraycopy(this.licence_key, 0, crypt_key, 0,
						this.licence_key.length);
				rc4_licence.init(true, new KeyParameter(crypt_key));
				rc4_licence.processBytes(hwid, 0, LICENCE_HWID_SIZE, crypt_hwid, 0);

				present(null_data, null_data, licence_data,
						licence_data.length, crypt_hwid, signature);
				LOGGER.debug("Presented stored licence to server!");
				return;
			}
		}
		this.send_request(null_data, null_data, user, host);
	}

	/**
	 * Handle an authorisation request, based on a licence signature (store
	 * signatures in this Licence object
	 *
	 * @param data
	 *            Packet containing details of request
	 * @return True if signature is read successfully
	 * @throws RdesktopException
	 */
	public boolean parse_authreq(RdpPacket data)
			throws RdesktopException {

		int tokenlen = 0;

		data.incrementPosition(6); // unknown

		tokenlen = data.getLittleEndian16();

		if (tokenlen != LICENCE_TOKEN_SIZE) {
			throw new RdesktopException("Wrong Tokenlength!");
		}
		this.in_token = new byte[tokenlen];
		data.copyToByteArray(this.in_token, 0, data.getPosition(), tokenlen);
		data.incrementPosition(tokenlen);
		this.in_sig = new byte[LICENCE_SIGNATURE_SIZE];
		data.copyToByteArray(this.in_sig, 0, data.getPosition(),
				LICENCE_SIGNATURE_SIZE);
		data.incrementPosition(LICENCE_SIGNATURE_SIZE);

		if (data.getPosition() == data.getEnd()) {
			return true;
		} else {
			return false;
		}
	}

	/**
	 * Respond to authorisation request, with token, hwid and signature, send
	 * response to server
	 *
	 * @param token
	 *            Token data
	 * @param crypt_hwid
	 *            HWID for encryption
	 * @param signature
	 *            Signature data
	 * @throws RdesktopException
	 * @throws IOException
	 */
	public void send_authresp(byte[] token, byte[] crypt_hwid, byte[] signature)
			throws RdesktopException, IOException {
		int sec_flags = Secure.SEC_LICENCE_NEG;
		int length = 58;
		RdpPacket data = null;

		data = secure.init(sec_flags, length + 2);

		data.set8(LICENCE_TAG_AUTHRESP);
		data.set8(2); // version
		data.setLittleEndian16(length);

		data.setLittleEndian16(1);
		data.setLittleEndian16(LICENCE_TOKEN_SIZE);
		data
		.copyFromByteArray(token, 0, data.getPosition(),
				LICENCE_TOKEN_SIZE);
		data.incrementPosition(LICENCE_TOKEN_SIZE);

		data.setLittleEndian16(1);
		data.setLittleEndian16(LICENCE_HWID_SIZE);
		data.copyFromByteArray(crypt_hwid, 0, data.getPosition(),
				LICENCE_HWID_SIZE);
		data.incrementPosition(LICENCE_HWID_SIZE);

		data.copyFromByteArray(signature, 0, data.getPosition(),
				LICENCE_SIGNATURE_SIZE);
		data.incrementPosition(LICENCE_SIGNATURE_SIZE);
		data.markEnd();
		secure.send(data, sec_flags);
	}

	/**
	 * Present a licence to the server
	 *
	 * @param client_random
	 * @param rsa_data
	 * @param licence_data
	 * @param licence_size
	 * @param hwid
	 * @param signature
	 * @throws RdesktopException
	 * @throws IOException
	 */
	public void present(byte[] client_random, byte[] rsa_data,
			byte[] licence_data, int licence_size, byte[] hwid, byte[] signature)
					throws RdesktopException, IOException {
		int sec_flags = Secure.SEC_LICENCE_NEG;
		int length = /* rdesktop is 16 not 20, but this must be wrong?! */
				20 + Secure.SEC_RANDOM_SIZE + Secure.SEC_MODULUS_SIZE
				+ Secure.SEC_PADDING_SIZE + licence_size + LICENCE_HWID_SIZE
				+ LICENCE_SIGNATURE_SIZE;

		RdpPacket s = secure.init(sec_flags, length + 4);

		s.set8(LICENCE_TAG_PRESENT);
		s.set8(2); // version
		s.setLittleEndian16(length);

		s.setLittleEndian32(1);
		s.setLittleEndian16(0);
		s.setLittleEndian16(0x0201);

		s.copyFromByteArray(client_random, 0, s.getPosition(),
				Secure.SEC_RANDOM_SIZE);
		s.incrementPosition(Secure.SEC_RANDOM_SIZE);
		s.setLittleEndian16(0);
		s
		.setLittleEndian16((Secure.SEC_MODULUS_SIZE + Secure.SEC_PADDING_SIZE));
		s.copyFromByteArray(rsa_data, 0, s.getPosition(),
				Secure.SEC_MODULUS_SIZE);
		s.incrementPosition(Secure.SEC_MODULUS_SIZE);
		s.incrementPosition(Secure.SEC_PADDING_SIZE);

		s.setLittleEndian16(1);
		s.setLittleEndian16(licence_size);
		s.copyFromByteArray(licence_data, 0, s.getPosition(), licence_size);
		s.incrementPosition(licence_size);

		s.setLittleEndian16(1);
		s.setLittleEndian16(LICENCE_HWID_SIZE);
		s.copyFromByteArray(hwid, 0, s.getPosition(), LICENCE_HWID_SIZE);
		s.incrementPosition(LICENCE_HWID_SIZE);
		s.copyFromByteArray(signature, 0, s.getPosition(),
				LICENCE_SIGNATURE_SIZE);
		s.incrementPosition(LICENCE_SIGNATURE_SIZE);

		s.markEnd();
		secure.send(s, sec_flags);
	}

	/**
	 * Process an authorisation request
	 *
	 * @param data
	 *            Packet containing request details
	 * @throws RdesktopException
	 * @throws UnsupportedEncodingException
	 * @throws IOException
	 */
	public void process_authreq(RdpPacket data)
			throws RdesktopException, UnsupportedEncodingException,
			IOException {

		byte[] out_token = new byte[LICENCE_TOKEN_SIZE];
		byte[] decrypt_token = new byte[LICENCE_TOKEN_SIZE];

		byte[] crypt_hwid = new byte[LICENCE_HWID_SIZE];
		byte[] sealed_buffer = new byte[LICENCE_TOKEN_SIZE + LICENCE_HWID_SIZE];
		byte[] out_sig = new byte[LICENCE_SIGNATURE_SIZE];
		RC4Engine rc4_licence = new RC4Engine();
		byte[] crypt_key = null;

		/* parse incoming packet and save encrypted token */
		if (parse_authreq(data) != true) {
			throw new RdesktopException("Authentication Request was corrupt!");
		}
		System.arraycopy(this.in_token, 0, out_token, 0, LICENCE_TOKEN_SIZE);

		/* decrypt token. It should read TEST in Unicode */
		crypt_key = new byte[this.licence_key.length];
		System.arraycopy(this.licence_key, 0, crypt_key, 0,
				this.licence_key.length);
		rc4_licence.init(false, new KeyParameter(crypt_key));
		rc4_licence.processBytes(this.in_token, 0, LICENCE_TOKEN_SIZE, decrypt_token, 0);

		/* construct HWID */
		byte[] hwid = this.generate_hwid();

		/* generate signature for a buffer of token and HWId */
		System
		.arraycopy(decrypt_token, 0, sealed_buffer, 0,
				LICENCE_TOKEN_SIZE);
		System.arraycopy(hwid, 0, sealed_buffer, LICENCE_TOKEN_SIZE,
				LICENCE_HWID_SIZE);

		out_sig = secure.sign(this.licence_sign_key, 16, 16, sealed_buffer,
				sealed_buffer.length);

		/* deliberately break signature if licencing disabled */
		if (!options.use_real_license_signature) {
			out_sig = new byte[LICENCE_SIGNATURE_SIZE]; // set to 0
		}

		/* now crypt the hwid */
		System.arraycopy(this.licence_key, 0, crypt_key, 0,
				this.licence_key.length);
		rc4_licence.init(true, new KeyParameter(crypt_key));
		rc4_licence.processBytes(hwid, 0, LICENCE_HWID_SIZE, crypt_hwid, 0);

		this.send_authresp(out_token, crypt_hwid, out_sig);
	}

	/**
	 * Handle a licence issued by the server, save to disk if
	 * options.save_licence
	 *
	 * @param data
	 *            Packet containing issued licence
	 */
	public void process_issue(RdpPacket data) {
		int length = 0;
		int check = 0;
		RC4Engine rc4_licence = new RC4Engine();
		byte[] key = new byte[this.licence_key.length];
		System.arraycopy(this.licence_key, 0, key, 0, this.licence_key.length);

		data.incrementPosition(2); // unknown
		length = data.getLittleEndian16();

		if (data.getPosition() + length > data.getEnd()) {
			return;
		}

		rc4_licence.init(false, new KeyParameter(key));
		byte[] buffer = new byte[length];
		data.copyToByteArray(buffer, 0, data.getPosition(), length);
		rc4_licence.processBytes(buffer, 0, length, buffer, 0);
		data.copyFromByteArray(buffer, 0, data.getPosition(), length);

		check = data.getLittleEndian16();
		if (check != 0) {
			// return;
		}
		secure.licenceIssued = true;

		/*
		 * data.incrementPosition(2); // in_uint8s(s, 2); // pad
		 *  // advance to fourth string length = 0; for (int i = 0; i < 4; i++) {
		 * data.incrementPosition(length); // in_uint8s(s, length); length =
		 * data.getLittleEndian32(length); // in_uint32_le(s, length); if
		 * (!(data.getPosition() + length <= data.getEnd())) return; }
		 */

		secure.licenceIssued = true;
		LOGGER.debug("Server issued Licence");
		if (options.save_licence) {
			save_licence(data, length - 2);
		}
	}

	/**
	 * Send a request for a new licence, or to approve a stored licence
	 *
	 * @param client_random
	 * @param rsa_data
	 * @param username
	 * @param hostname
	 * @throws RdesktopException
	 * @throws IOException
	 */
	public void send_request(byte[] client_random, byte[] rsa_data,
			byte[] username, byte[] hostname) throws RdesktopException,
			IOException {
		int sec_flags = Secure.SEC_LICENCE_NEG;
		int userlen = (username.length == 0 ? 0 : username.length + 1);
		int hostlen = (hostname.length == 0 ? 0 : hostname.length + 1);
		int length = 128 + userlen + hostlen;

		RdpPacket buffer = secure.init(sec_flags, length);

		buffer.set8(LICENCE_TAG_REQUEST);
		buffer.set8(2); // version
		buffer.setLittleEndian16(length);

		buffer.setLittleEndian32(1);

		if (options.built_in_licence && (!options.load_licence)
				&& (!options.save_licence)) {
			LOGGER.debug("Using built-in Windows Licence");
			buffer.setLittleEndian32(0x03010000);
		} else {
			LOGGER.debug("Requesting licence");
			buffer.setLittleEndian32(0xff010000);
		}
		buffer.copyFromByteArray(client_random, 0, buffer.getPosition(),
				Secure.SEC_RANDOM_SIZE);
		buffer.incrementPosition(Secure.SEC_RANDOM_SIZE);
		buffer.setLittleEndian16(0);

		buffer.setLittleEndian16(Secure.SEC_MODULUS_SIZE
				+ Secure.SEC_PADDING_SIZE);
		buffer.copyFromByteArray(rsa_data, 0, buffer.getPosition(),
				Secure.SEC_MODULUS_SIZE);
		buffer.incrementPosition(Secure.SEC_MODULUS_SIZE);

		buffer.incrementPosition(Secure.SEC_PADDING_SIZE);

		buffer.setLittleEndian16(LICENCE_TAG_USER);
		buffer.setLittleEndian16(userlen);

		if (username.length != 0) {
			buffer.copyFromByteArray(username, 0, buffer.getPosition(),
					userlen - 1);
		} else {
			buffer
			.copyFromByteArray(username, 0, buffer.getPosition(),
					userlen);
		}

		buffer.incrementPosition(userlen);

		buffer.setLittleEndian16(LICENCE_TAG_HOST);
		buffer.setLittleEndian16(hostlen);

		if (hostname.length != 0) {
			buffer.copyFromByteArray(hostname, 0, buffer.getPosition(),
					hostlen - 1);
		} else {
			buffer
			.copyFromByteArray(hostname, 0, buffer.getPosition(),
					hostlen);
		}
		buffer.incrementPosition(hostlen);
		buffer.markEnd();
		secure.send(buffer, sec_flags);
	}

	/**
	 * Load a licence from disk
	 *
	 * @return Raw byte data for stored licence
	 */
	private byte[] load_licence() {
		LOGGER.debug("load_licence");

		return LicenceStore.load_licence(options);
	}

	/**
	 * Save a licence to disk
	 *
	 * @param data
	 *            Packet containing licence data
	 * @param length
	 *            Length of licence
	 */
	private void save_licence(RdpPacket data, int length) {
		LOGGER.debug("save_licence");
		int len;
		int startpos = data.getPosition();
		data.incrementPosition(2); // Skip first two bytes
		/* Skip three strings */
		for (int i = 0; i < 3; i++) {
			len = data.getLittleEndian32();
			data.incrementPosition(len);
			/*
			 * Make sure that we won't be past the end of data after reading the
			 * next length value
			 */
			if (data.getPosition() + 4 - startpos > length) {
				LOGGER.warn("Error in parsing licence key.");
				return;
			}
		}
		len = data.getLittleEndian32();
		LOGGER.debug("save_licence: len=" + len);
		if (data.getPosition() + len - startpos > length) {
			LOGGER.warn("Error in parsing licence key.");
			return;
		}

		byte[] databytes = new byte[len];
		data.copyToByteArray(databytes, 0, data.getPosition(), len);

		LicenceStore.save_licence(options, databytes);
	}

	/**
	 * Generate a set of encryption keys
	 *
	 * @param client_key
	 *            Array in which to store client key
	 * @param server_key
	 *            Array in which to store server key
	 * @param client_rsa
	 *            Array in which to store RSA data
	 */
	public void generate_keys(byte[] client_key, byte[] server_key,
			byte[] client_rsa) {
		byte[] session_key = new byte[48];
		byte[] temp_hash = new byte[48];

		temp_hash = secure.hash48(client_rsa, client_key, server_key, 65);
		session_key = secure.hash48(temp_hash, server_key, client_key, 65);

		System.arraycopy(session_key, 0, this.licence_sign_key, 0, 16);

		this.licence_key = secure.hash16(session_key, client_key, server_key,
				16);
	}
}
