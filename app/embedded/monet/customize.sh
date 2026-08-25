# shellcheck disable=SC2034
SKIPUNZIP=0

ui_print " "
ui_print '             _       __     __ __ _ __'
ui_print '            | |     / /__  / //_/(_) /_'
ui_print '            | | /| / / _ \/ ,<  / / __/'
ui_print '            | |/ |/ /  __/ /| |/ / /_'
ui_print '            |__/|__/\___/_/ |_/_/\__/'
ui_print " "
ui_print "       [WeKit] WeChat, now with superpowers"
ui_print " "
ui_print "已安装生成时选定的 S4 Monet 覆盖。"
ui_print " "
ui_print "温馨提示:"
ui_print "- 若正在使用 KernelSU 或 APatch 及其衍生版, 请禁用「微信」的「App Profile」中的「卸载模块」选项。"
ui_print "- 无须禁用「默认卸载模块」。"
ui_print "- 若仍不生效, 请尝试给予「微信」Root 权限。"

set_perm "$MODPATH/module.prop" 0 0 0644
set_perm "$MODPATH/config.conf" 0 0 0644
set_perm "$MODPATH/monet-resolution.json" 0 0 0644
set_perm "$MODPATH/customize.sh" 0 0 0755
set_perm "$MODPATH/common.sh" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/boot-completed.sh" 0 0 0755
[ -d "$MODPATH/system" ] && set_perm_recursive "$MODPATH/system" 0 0 0755 0644
