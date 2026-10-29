package com.memorypalace.client.crypto;

import com.memorypalace.MemoryPalace;
import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.ptr.PointerByReference;
import net.minecraft.network.chat.Component;

import java.lang.ref.Reference;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

/**
 * 实体钥匙（YubiKey 等 FIDO2 安全密钥）：通过 Windows 自带的 webauthn.dll 调用，所以只支持 Windows。
 * 插钥匙、输 PIN、触摸都由 Windows 的对话框引导，模组不碰 PIN。
 * <p>
 * 用的是 WebAuthn 的 PRF 扩展（底层是 CTAP 的 hmac-secret）：给钥匙一个 32 字节的盐，钥匙用凭据里的密钥算出固定的 32 字节结果，
 * 同一把钥匙、同一个凭据、同一个盐每次都一样，换一把钥匙就不一样。这个结果用来锁主密钥（见 KeyRing.withToken）。
 * 每次都要求验证 PIN（用户验证），所以光拿到钥匙不够。
 * <p>
 * 结构体照 microsoft/webauthn 的 webauthn.h（MIT）抄：只写到用得着的版本，dwVersion 设成对应版本，Windows 只读到那一版的字段。
 * 调用会一直等到用户操作完（最多 TIMEOUT_MS），不要在渲染线程上调用。
 */
public final class SecurityKey {
	/** 依赖方 ID。只是钥匙上区分"这是哪个应用的凭据"的名字，不涉及网络。 */
	static final String RP_ID = "memorypalace.local";
	private static final String ORIGIN = "https://" + RP_ID;
	private static final int TIMEOUT_MS = 120_000;
	// 需要 GetAssertion 选项第 6 版（带 PRF 盐）和断言结果第 3 版（带 PRF 结果）：WEBAUTHN_API_VERSION_4 起
	private static final int MIN_API = 4;

	private static final int USER_VERIFICATION_REQUIRED = 1;
	private static final int ATTACHMENT_ANY = 0;
	private static final int ATTACHMENT_CROSS_PLATFORM = 2;
	private static final int ATTESTATION_NONE = 1;
	private static final int ES256 = -7;
	private static final SecureRandom RANDOM = new SecureRandom();

	private SecurityKey() {}

	/** 操作失败：message 是给玩家看的原因。 */
	public static final class Failure extends Exception {
		private final transient Component message;

		Failure(Component message) {
			super(message.getString());
			this.message = message;
		}

		public Component message() {
			return message;
		}
	}

	private static volatile Boolean available;

	/** 这台电脑能不能用实体钥匙（Windows，而且 webauthn.dll 够新）。 */
	public static boolean available() {
		Boolean a = available;
		if (a == null) {
			a = false;
			if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
				try {
					a = Api.INSTANCE.WebAuthNGetApiVersionNumber() >= MIN_API;
				} catch (Throwable e) {
					MemoryPalace.LOGGER.warn("无法加载 webauthn.dll：{}", e.toString());
				}
			}
			available = a;
		}
		return a;
	}

	/** 登记：在钥匙上建一个新凭据（开启 PRF），返回凭据 ID。 */
	public static byte[] register() throws Failure {
		int api = Api.INSTANCE.WebAuthNGetApiVersionNumber();
		RpInfo rp = new RpInfo();
		rp.dwVersion = 1;
		rp.pwszId = new WString(RP_ID);
		rp.pwszName = new WString("Memory Palace");

		Memory userId = bytes(random(16));
		UserInfo user = new UserInfo();
		user.dwVersion = 1;
		user.cbId = (int) userId.size();
		user.pbId = userId;
		user.pwszName = new WString("memory-palace");
		user.pwszDisplayName = new WString("记忆宫殿保险库");

		CoseParam es256 = new CoseParam();
		es256.dwVersion = 1;
		es256.pwszCredentialType = new WString("public-key");
		es256.lAlg = ES256;
		es256.write();
		CoseParams params = new CoseParams();
		params.cCredentialParameters = 1;
		params.pCredentialParameters = es256.getPointer();

		ClientDataHolder clientData = clientData("webauthn.create");

		// 扩展 "hmac-secret" = TRUE：让钥匙给这个凭据生成 PRF 用的密钥
		Memory enable = new Memory(4);
		enable.setInt(0, 1);
		Extension hmac = new Extension();
		hmac.pwszExtensionIdentifier = new WString("hmac-secret");
		hmac.cbExtension = 4;
		hmac.pvExtension = enable;
		hmac.write();

		MakeOptions options = new MakeOptions();
		options.dwVersion = api >= 6 ? 6 : 4;
		options.dwTimeoutMilliseconds = TIMEOUT_MS;
		options.Extensions.cExtensions = 1;
		options.Extensions.pExtensions = hmac.getPointer();
		options.dwAuthenticatorAttachment = ATTACHMENT_CROSS_PLATFORM;
		options.dwUserVerificationRequirement = USER_VERIFICATION_REQUIRED;
		options.dwAttestationConveyancePreference = ATTESTATION_NONE;
		options.bEnablePrf = 1;

		PointerByReference out = new PointerByReference();
		try {
			check(Api.INSTANCE.WebAuthNAuthenticatorMakeCredential(window(), rp, user, params, clientData.data, options, out));
		} finally {
			// 这些只通过指针被引用：调用结束前不能被回收（否则 Windows 读到的是已释放的内存）
			Reference.reachabilityFence(es256);
			Reference.reachabilityFence(hmac);
			Reference.reachabilityFence(enable);
			Reference.reachabilityFence(userId);
			Reference.reachabilityFence(clientData);
		}
		Pointer result = out.getValue();
		if (result == null) {
			throw failure("token_failed");
		}
		try {
			Attestation attestation = new Attestation(result);
			if (attestation.cbCredentialId <= 0 || attestation.cbCredentialId > 1023 || attestation.pbCredentialId == null) {
				throw failure("token_failed");
			}
			return attestation.pbCredentialId.getByteArray(0, attestation.cbCredentialId);
		} finally {
			Api.INSTANCE.WebAuthNFreeCredentialAttestation(result);
		}
	}

	/** 用登记过的凭据对 salt（32 字节）算 PRF，返回 32 字节结果。不是那把钥匙时 Windows 会提示找不到凭据。 */
	public static byte[] prf(byte[] credentialId, byte[] salt) throws Failure {
		Memory credBytes = bytes(credentialId);
		Credential credential = new Credential();
		credential.dwVersion = 1;
		credential.cbId = (int) credBytes.size();
		credential.pbId = credBytes;
		credential.pwszCredentialType = new WString("public-key");
		credential.write();

		Memory saltBytes = bytes(salt);
		HmacSalt hmacSalt = new HmacSalt();
		hmacSalt.cbFirst = (int) saltBytes.size();
		hmacSalt.pbFirst = saltBytes;
		hmacSalt.write();
		HmacSaltValues saltValues = new HmacSaltValues();
		saltValues.pGlobalHmacSalt = hmacSalt.getPointer();
		saltValues.write();

		ClientDataHolder clientData = clientData("webauthn.get");
		GetOptions options = new GetOptions();
		options.dwVersion = 6;
		options.dwTimeoutMilliseconds = TIMEOUT_MS;
		options.CredentialList.cCredentials = 1;
		options.CredentialList.pCredentials = credential.getPointer();
		options.dwAuthenticatorAttachment = ATTACHMENT_ANY;
		options.dwUserVerificationRequirement = USER_VERIFICATION_REQUIRED;
		options.pHmacSecretSaltValues = saltValues.getPointer();

		PointerByReference out = new PointerByReference();
		try {
			check(Api.INSTANCE.WebAuthNAuthenticatorGetAssertion(window(), new WString(RP_ID), clientData.data, options, out));
		} finally {
			Reference.reachabilityFence(credential);
			Reference.reachabilityFence(credBytes);
			Reference.reachabilityFence(hmacSalt);
			Reference.reachabilityFence(saltBytes);
			Reference.reachabilityFence(saltValues);
			Reference.reachabilityFence(clientData);
		}
		Pointer result = out.getValue();
		if (result == null) {
			throw failure("token_failed");
		}
		try {
			Assertion assertion = new Assertion(result);
			if (assertion.dwVersion < 3 || assertion.pHmacSecret == null) {
				throw failure("token_no_prf");
			}
			HmacSalt output = new HmacSalt(assertion.pHmacSecret);
			if (output.cbFirst != 32 || output.pbFirst == null) {
				throw failure("token_no_prf");
			}
			return output.pbFirst.getByteArray(0, 32);
		} finally {
			Api.INSTANCE.WebAuthNFreeAssertion(result);
		}
	}

	private static void check(int hr) throws Failure {
		if (hr == 0) {
			return;
		}
		String name;
		try {
			Pointer p = Api.INSTANCE.WebAuthNGetErrorName(hr);
			name = p == null ? "" : p.getWideString(0);
		} catch (Throwable e) {
			name = "";
		}
		MemoryPalace.LOGGER.info("实体钥匙操作没有完成：{}（0x{}）", name, Integer.toHexString(hr));
		throw failure(switch (name) {
			case "NotAllowedError" -> "token_cancelled"; // 取消、超时、找不到这把钥匙上的凭据
			case "InvalidStateError" -> "token_invalid_state";
			case "NotSupportedError", "ConstraintError" -> "token_not_supported";
			default -> "token_failed";
		});
	}

	private static Failure failure(String key) {
		return new Failure(Component.translatable("message.memorypalace." + key));
	}

	/** 当前前台窗口（玩家刚点了按钮，就是游戏窗口）：Windows 的对话框挂在它上面。 */
	private static Pointer window() {
		return User32.INSTANCE.GetForegroundWindow();
	}

	private static byte[] random(int length) {
		byte[] b = new byte[length];
		RANDOM.nextBytes(b);
		return b;
	}

	private static Memory bytes(byte[] data) {
		Memory m = new Memory(data.length);
		m.write(0, data, 0, data.length);
		return m;
	}

	/** 客户端数据（JSON）连同它引用的内存：调用结束前都要留着引用，不能被回收。 */
	private record ClientDataHolder(ClientData data, Memory json) {}

	private static ClientDataHolder clientData(String type) {
		String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(random(32));
		byte[] json = ("{\"type\":\"" + type + "\",\"challenge\":\"" + challenge + "\",\"origin\":\"" + ORIGIN
				+ "\",\"crossOrigin\":false}").getBytes(StandardCharsets.UTF_8);
		Memory memory = bytes(json);
		ClientData data = new ClientData();
		data.dwVersion = 1;
		data.cbClientDataJSON = json.length;
		data.pbClientDataJSON = memory;
		data.pwszHashAlgId = new WString("SHA-256");
		return new ClientDataHolder(data, memory);
	}

	// ---- webauthn.dll ----

	private interface Api extends Library {
		Api INSTANCE = Native.load("webauthn", Api.class);

		int WebAuthNGetApiVersionNumber();

		int WebAuthNAuthenticatorMakeCredential(Pointer hWnd, RpInfo rp, UserInfo user, CoseParams params, ClientData clientData,
				MakeOptions options, PointerByReference attestation);

		int WebAuthNAuthenticatorGetAssertion(Pointer hWnd, WString rpId, ClientData clientData, GetOptions options,
				PointerByReference assertion);

		void WebAuthNFreeCredentialAttestation(Pointer attestation);

		void WebAuthNFreeAssertion(Pointer assertion);

		Pointer WebAuthNGetErrorName(int hr);
	}

	private interface User32 extends Library {
		User32 INSTANCE = Native.load("user32", User32.class);

		Pointer GetForegroundWindow();
	}

	@Structure.FieldOrder({"dwVersion", "pwszId", "pwszName", "pwszIcon"})
	public static class RpInfo extends Structure {
		public int dwVersion;
		public WString pwszId;
		public WString pwszName;
		public WString pwszIcon;
	}

	@Structure.FieldOrder({"dwVersion", "cbId", "pbId", "pwszName", "pwszIcon", "pwszDisplayName"})
	public static class UserInfo extends Structure {
		public int dwVersion;
		public int cbId;
		public Pointer pbId;
		public WString pwszName;
		public WString pwszIcon;
		public WString pwszDisplayName;
	}

	@Structure.FieldOrder({"dwVersion", "pwszCredentialType", "lAlg"})
	public static class CoseParam extends Structure {
		public int dwVersion;
		public WString pwszCredentialType;
		public int lAlg;
	}

	@Structure.FieldOrder({"cCredentialParameters", "pCredentialParameters"})
	public static class CoseParams extends Structure {
		public int cCredentialParameters;
		public Pointer pCredentialParameters;
	}

	@Structure.FieldOrder({"dwVersion", "cbClientDataJSON", "pbClientDataJSON", "pwszHashAlgId"})
	public static class ClientData extends Structure {
		public int dwVersion;
		public int cbClientDataJSON;
		public Pointer pbClientDataJSON;
		public WString pwszHashAlgId;
	}

	@Structure.FieldOrder({"dwVersion", "cbId", "pbId", "pwszCredentialType"})
	public static class Credential extends Structure {
		public int dwVersion;
		public int cbId;
		public Pointer pbId;
		public WString pwszCredentialType;
	}

	/** WEBAUTHN_CREDENTIALS（嵌在选项结构里，按值）。 */
	@Structure.FieldOrder({"cCredentials", "pCredentials"})
	public static class Credentials extends Structure {
		public int cCredentials;
		public Pointer pCredentials;
	}

	@Structure.FieldOrder({"pwszExtensionIdentifier", "cbExtension", "pvExtension"})
	public static class Extension extends Structure {
		public WString pwszExtensionIdentifier;
		public int cbExtension;
		public Pointer pvExtension;
	}

	/** WEBAUTHN_EXTENSIONS（嵌在选项、结果结构里，按值）。 */
	@Structure.FieldOrder({"cExtensions", "pExtensions"})
	public static class Extensions extends Structure {
		public int cExtensions;
		public Pointer pExtensions;
	}

	/** WEBAUTHN_AUTHENTICATOR_MAKE_CREDENTIAL_OPTIONS，到第 6 版（bEnablePrf）为止。 */
	@Structure.FieldOrder({"dwVersion", "dwTimeoutMilliseconds", "CredentialList", "Extensions", "dwAuthenticatorAttachment",
			"bRequireResidentKey", "dwUserVerificationRequirement", "dwAttestationConveyancePreference", "dwFlags",
			"pCancellationId", "pExcludeCredentialList", "dwEnterpriseAttestation", "dwLargeBlobSupport", "bPreferResidentKey",
			"bBrowserInPrivateMode", "bEnablePrf"})
	public static class MakeOptions extends Structure {
		public int dwVersion;
		public int dwTimeoutMilliseconds;
		public Credentials CredentialList = new Credentials();
		public Extensions Extensions = new Extensions();
		public int dwAuthenticatorAttachment;
		public int bRequireResidentKey;
		public int dwUserVerificationRequirement;
		public int dwAttestationConveyancePreference;
		public int dwFlags;
		public Pointer pCancellationId;
		public Pointer pExcludeCredentialList;
		public int dwEnterpriseAttestation;
		public int dwLargeBlobSupport;
		public int bPreferResidentKey;
		public int bBrowserInPrivateMode;
		public int bEnablePrf;
	}

	/** WEBAUTHN_AUTHENTICATOR_GET_ASSERTION_OPTIONS，到第 6 版（pHmacSecretSaltValues）为止。 */
	@Structure.FieldOrder({"dwVersion", "dwTimeoutMilliseconds", "CredentialList", "Extensions", "dwAuthenticatorAttachment",
			"dwUserVerificationRequirement", "dwFlags", "pwszU2fAppId", "pbU2fAppId", "pCancellationId", "pAllowCredentialList",
			"dwCredLargeBlobOperation", "cbCredLargeBlob", "pbCredLargeBlob", "pHmacSecretSaltValues", "bBrowserInPrivateMode"})
	public static class GetOptions extends Structure {
		public int dwVersion;
		public int dwTimeoutMilliseconds;
		public Credentials CredentialList = new Credentials();
		public Extensions Extensions = new Extensions();
		public int dwAuthenticatorAttachment;
		public int dwUserVerificationRequirement;
		public int dwFlags;
		public WString pwszU2fAppId;
		public Pointer pbU2fAppId;
		public Pointer pCancellationId;
		public Pointer pAllowCredentialList;
		public int dwCredLargeBlobOperation;
		public int cbCredLargeBlob;
		public Pointer pbCredLargeBlob;
		public Pointer pHmacSecretSaltValues;
		public int bBrowserInPrivateMode;
	}

	@Structure.FieldOrder({"cbFirst", "pbFirst", "cbSecond", "pbSecond"})
	public static class HmacSalt extends Structure {
		public int cbFirst;
		public Pointer pbFirst;
		public int cbSecond;
		public Pointer pbSecond;

		public HmacSalt() {}

		HmacSalt(Pointer p) {
			super(p);
			read();
		}
	}

	@Structure.FieldOrder({"pGlobalHmacSalt", "cCredWithHmacSecretSaltList", "pCredWithHmacSecretSaltList"})
	public static class HmacSaltValues extends Structure {
		public Pointer pGlobalHmacSalt;
		public int cCredWithHmacSecretSaltList;
		public Pointer pCredWithHmacSecretSaltList;
	}

	/** WEBAUTHN_CREDENTIAL_ATTESTATION 的前半段（到凭据 ID 为止，第 1 版就有）。只读，内存归 Windows。 */
	@Structure.FieldOrder({"dwVersion", "pwszFormatType", "cbAuthenticatorData", "pbAuthenticatorData", "cbAttestation",
			"pbAttestation", "dwAttestationDecodeType", "pvAttestationDecode", "cbAttestationObject", "pbAttestationObject",
			"cbCredentialId", "pbCredentialId"})
	public static class Attestation extends Structure {
		public int dwVersion;
		public Pointer pwszFormatType;
		public int cbAuthenticatorData;
		public Pointer pbAuthenticatorData;
		public int cbAttestation;
		public Pointer pbAttestation;
		public int dwAttestationDecodeType;
		public Pointer pvAttestationDecode;
		public int cbAttestationObject;
		public Pointer pbAttestationObject;
		public int cbCredentialId;
		public Pointer pbCredentialId;

		Attestation(Pointer p) {
			super(p);
			read();
		}
	}

	/** WEBAUTHN_ASSERTION 的前半段（到第 3 版的 pHmacSecret 为止）。只读，内存归 Windows。 */
	@Structure.FieldOrder({"dwVersion", "cbAuthenticatorData", "pbAuthenticatorData", "cbSignature", "pbSignature",
			"Credential", "cbUserId", "pbUserId", "Extensions", "cbCredLargeBlob", "pbCredLargeBlob", "dwCredLargeBlobStatus",
			"pHmacSecret"})
	public static class Assertion extends Structure {
		public int dwVersion;
		public int cbAuthenticatorData;
		public Pointer pbAuthenticatorData;
		public int cbSignature;
		public Pointer pbSignature;
		public Credential Credential = new Credential();
		public int cbUserId;
		public Pointer pbUserId;
		public Extensions Extensions = new Extensions();
		public int cbCredLargeBlob;
		public Pointer pbCredLargeBlob;
		public int dwCredLargeBlobStatus;
		public Pointer pHmacSecret;

		Assertion(Pointer p) {
			super(p);
			read();
		}
	}
}
