# 核心项目地图

项目根目录为包含本文件的仓库code - anker。使用根目录.venv-track/Scripts/python.exe。

当前只有A自动建档→B货架交互及匿名外观关联这一主流程。重要参数统一configs/vision_runtime.json，货架多边形由shelf_calibration.html导出到configs/interaction/shelf_B_*.json。默认选择最新保存的B标定。

执行run_AB_station.ps1先完成A，再由B读A快照。人物档案根目录data/person_enrollment，G编号独立目录，3～5张有效代表图；旧少样本档案保留并注明。缺乏身份支持时person_id=null。五帧优先首次伸手/峰值/收手；腕点仅触发，不直接定层。API由ReviewWithQwen显式启用。

输出是outputs/<RunName>_A/B，查看comparison.html、视频、events.json及keyframes。六字段契约不变。迁移验证结果见docs/核心副本_整理记录_20261016.md，完整参数字典见docs/统一参数与人物档案_20261016.md。

旧Florence/KLT/RetailAction/YOLOE及其权重、历史输出/旧视频均已移出本项目，不使用旧文档命令。原code保留完整历史。Python源码只由当前副本加载；使用唯一环境执行unittest discover -s tests。
