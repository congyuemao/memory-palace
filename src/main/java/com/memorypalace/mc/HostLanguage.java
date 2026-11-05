package com.memorypalace.mc;

import java.util.Locale;

/** 只能存成字符串的文本（书名、默认文件夹名）无法按看的人翻译，按运行服务端的系统语言选中文或英文。 */
final class HostLanguage {
	private static final boolean ZH = "zh".equals(Locale.getDefault().getLanguage());

	private HostLanguage() {
	}

	static String pick(String zh, String en) {
		return ZH ? zh : en;
	}
}
