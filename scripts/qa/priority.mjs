// Priority ordering for the QA campaign: mainstream, login-gated apps first so the
// pass reveals the most compatibility signal early; the rest follow automatically.
export const PRIORITY = [
  // social / messaging
  'com.tencent.mm', 'com.tencent.mobileqq', 'com.sina.weibo', 'com.xingin.xhs', 'org.telegram.messenger',
  'com.zhihu.android', 'com.taou.maimai', 'com.hpbr.bosszhipin', 'com.ss.android.article.news', 'com.ss.android.article.lite',
  'com.tencent.news', 'com.duokan.reader', 'com.phoenix.read', 'com.dragon.read', 'com.fenxiang.storyline',
  // e-commerce / life
  'com.taobao.taobao', 'com.jingdong.app.mall', 'com.xunmeng.pinduoduo', 'com.sankuai.meituan', 'com.sankuai.meituan.takeoutnew',
  'com.dianping.v1', 'com.taobao.idlefish', 'com.achievo.vipshop', 'com.alibaba.android.rimet', 'com.eg.android.AlipayGphone',
  'cmb.pb', 'com.mipay.wallet', 'com.xiaomi.youpin', 'com.xiaomi.shop', 'com.xiaomi.smarthome',
  // video / audio
  'tv.danmaku.bili', 'com.ss.android.ugc.aweme', 'com.smile.gifmaker', 'com.qiyi.video', 'com.tencent.qqlive',
  'com.hunantv.imgo.activity', 'com.netease.cloudmusic', 'com.luna.music', 'com.xs.fm', 'com.miui.video', 'com.miui.player',
  // travel / maps / car
  'ctrip.android.view', 'com.autonavi.minimap', 'com.baidu.BaiduMap', 'com.sdu.didi.psnger', 'com.tongcheng.android',
  'com.pangsheng.pshuochepiao.m', 'com.booking',
  // tools / browsers / storage
  'com.quark.browser', 'com.baidu.searchbox', 'com.baidu.netdisk', 'com.alicloud.databox', 'cn.wps.moffice_eng',
  'com.microsoft.office.officehubrow', 'com.kingsoft.xiezuo', 'org.videolan.vlc', 'com.xiaomi.scanner', 'com.deepseek.chat',
  'com.aliyun.tongyi', 'com.larus.nova', 'com.openai.chatgpt', 'com.anthropic.claude', 'com.google.android.apps.bard',
  'com.github.android', 'com.x8bit.bitwarden', 'com.google.android.apps.authenticator2', 'bbc.mobile.news.ww',
  'com.google.android.gm', 'com.android.chrome', 'com.google.android.youtube', 'com.instagram.android', 'com.twitter.android',
  // local services / finance
  'com.hundsun.winner.pazq', 'com.pingan.paces.ccms', 'com.mi.health', 'cn.cyberIdentity.certification', 'app.personal.linktools',
  // games
  'com.tencent.tmgp.sgame', 'com.tencent.tmgp.osgame', 'com.tencent.tmgp.WePop', 'com.miHoYo.Yuanshen', 'com.xiaomi.gamecenter',
];
