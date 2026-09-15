package com.piliplus.export;

import android.app.Activity;
import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;

/** 模块说明界面（供 LSPosed 管理器点开查看） */
public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        TextView tv = new TextView(this);
        tv.setTextSize(15);
        tv.setPadding(56, 56, 56, 56);
        tv.setText(
            "PiliPlus 导出模块（评论 + UP主）\n\n" +
            "【安装】\n" +
            "1. LSPosed 管理器 → 模块 → 勾选本模块\n" +
            "2. 作用域勾选 PiliPlus（或改名版/克隆版）\n" +
            "3. 强制停止 PiliPlus 再打开\n" +
            "4. 界面右侧出现两个按钮：导出评论 / 导出UP主\n\n" +
            "【功能一：导出评论】\n" +
            "复制视频 BV 号到剪贴板，或点按钮手输。\n" +
            "输出 /sdcard/Download/<视频标题>_评论.md\n" +
            "含主评论 + 全部楼中楼。\n\n" +
            "【功能二：导出UP主】\n" +
            "输入 UID 或粘贴主页链接，然后选模式：\n" +
            "  · 仅视频：清单 + 每个视频的全部评论\n" +
            "  · 仅动态：全部动态正文 + 发布时间\n" +
            "  · 视频 + 动态：两者都导\n" +
            "可勾选是否拉视频评论（不勾则 1~3 分钟完成）。\n\n" +
            "输出目录：/sdcard/Download/PiliPlus_导出/<UP名>/\n" +
            "    00_总览.md      UP信息 + 汇总 + 视频清单 + 动态清单\n" +
            "    动态.md         全部动态正文（含发布时间）\n" +
            "    视频/<标题>.md  每个视频的完整评论区\n\n" +
            "【耗时】\n" +
            "不含评论：通常 1~3 分钟\n" +
            "含评论：逐视频拉取，视视频数与评论量可能数小时\n" +
            "导出期间建议保持前台，锁屏可能被系统挂起。\n\n" +
            "【说明】\n" +
            "模块运行在目标进程内，使用该进程的登录态请求接口，\n" +
            "因此能拉到未登录看不到的深层评论。\n" +
            "动态走 web 接口 + WBI 签名（gRPC 响应中无发布时间字段）。\n" +
            "凭据仅在内存中使用，不落盘、不打印、不外传。"
        );
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        setContentView(sv);
    }
}
