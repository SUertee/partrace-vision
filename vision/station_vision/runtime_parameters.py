"""Documented, strictly validated knobs for A enrollment and B association."""
import copy
import json
from pathlib import Path
from urllib.parse import urlparse

DEFAULT_CONFIG='configs/vision_runtime.json'
SPECS={}


def knob(group,name,default,low,high,unit,meaning,increase,decrease,recommended=None):
    SPECS.setdefault(group,{})[name]={'default':default,'type':'integer' if type(default) is int else 'number',
        'range':[low,high],'unit':unit,'meaning':meaning,'increase':increase,'decrease':decrease,
        'recommended':recommended or [low,high]}


knob('a_video','sampling_fps',15,1,60,'fps','A人物检测/姿态目标采样频率，实际受源帧率限制。','更容易覆盖快速经过的人，推理耗时增加。','计算更少，但可能漏掉短暂经过或转身。',[10,20])
knob('a_video','feature_fps',5,1,30,'fps/人','A每条轨迹提取OSNet及选帧候选的最高频率。','候选更密，外观特征计算更多。','更省算力，短轨迹可能不足三张有效图。',[3,8])
knob('a_video','minimum_track_ms',500,0,10000,'ms','轨迹持续时间不足此值时不建档。','拒绝更多短轨迹，但可能漏掉快速经过者。','允许短暂经过者，误检轨迹更容易进入选帧。',[300,1000])

knob('a_direction','smooth_window_ms',200,0,2000,'ms','离线人物中心横坐标的前后中位数平滑半窗口；不需要门口分界线。','更抗检测框抖动，转折时间分辨率降低。','保留快速变化，也更容易受到单帧噪声影响。',[100,300])
knob('a_direction','minimum_samples',3,2,30,'观测数','确认一段横向移动所需的最少真实观测数量。','更稳，快速通过可能漏掉。','更快，也更容易受跟踪噪声影响。',[3,5])
knob('a_direction','stable_ms',200,0,3000,'ms','确认横向移动或反向转折所需的最短源时间跨度。','拒绝短促抖动，快速移动可能漏掉。','更快确认，短暂框变化更容易触发。',[150,400])
knob('a_direction','max_gap_ms',500,50,5000,'ms','同一轨迹方向判断可接受的最大观测间隔，超过即重置，不跨遮挡猜方向。','容忍稀疏观测，但跨缺失判断风险增加。','更保守，低帧率或遮挡下可能无事件。',[200,700])
knob('a_direction','minimum_displacement_ratio',.08,.01,.8,'画面宽度比例','判定左右移动或转身反向的最小中心横向位移；主要上下走且横向不足时不判断。','需要更明确的横向移动，小范围走动可能漏掉。','更容易触发，也更易受检测框变化影响。',[.06,.15])

for group,size in [('a_tracker',960),('b_tracker',640)]:
    knob(group,'imgsz',size,320,1280,'像素','人物姿态模型输入边长，必须为32的整数倍。','小人物细节更多，推理和显存开销增加；不保证更准确。','速度通常更快，小人物/姿态可能更差。',[640,960])
    knob(group,'detection_conf',0.1,0.01,1,'0～1','人物检测置信度下限。','候选更少，弱检测可能丢失。','保留弱检测，同时增加误检。',[0.1,0.3])
    knob(group,'track_high_thresh',0.25,0.01,1,'0～1','跟踪第一阶段高置信度检测门槛。','第一阶段更严格，轨迹可能更容易中断。','更多框参与第一阶段，可能增加误匹配。',[0.2,0.5])
    knob(group,'track_low_thresh',0.1,0.01,1,'0～1','跟踪第二阶段可使用的低置信度门槛，必须小于高门槛。','过滤更多低置信度框，遮挡恢复能力可能下降。','更容易延续遮挡轨迹，也更容易接入噪声。',[0.05,0.15])
    knob(group,'new_track_thresh',0.25,0.01,1,'0～1','新建局部人物轨迹的检测门槛。','新轨迹更少，可能漏掉短暂或弱检测的人。','新轨迹更多，误检/重复轨迹可能增加。',[0.2,0.5])
    knob(group,'match_thresh',0.8,0.1,1,'匹配代价上限','局部跟踪的数据关联代价门槛；不是OSNet相似度。','允许更大的关联代价，更容易续轨，也更容易串人。','关联更严格，可能产生更多断轨/新ID。',[0.6,0.9])
    knob(group,'lost_seconds',1.2,0.1,10,'s','局部跟踪保留丢失轨迹的时间；实现会转换为观测帧数。','允许更久遮挡恢复，但旧轨迹可能错误接续。','更快释放丢失轨迹，可能导致编号碎片。',[0.5,2])
    knob(group,'cpu_threads',4,1,32,'线程','PyTorch CPU计算线程数，CUDA运行仍有CPU工作。','可能提高CPU吞吐，也可能因争用更慢，需实测。','减少资源争用，但可能降低CPU吞吐。',[2,8])

for group,h,w,blur in [('a_quality',120,32,18.0),('b_quality',80,24,15.0)]:
    knob(group,'min_height',h,32,2160,'源图像素','人物裁剪最小高度。','保留更大人物，远处小人物可能被拒绝。','允许小人物，外观辨识通常更弱。',[80,200])
    knob(group,'min_width',w,12,1080,'源图像素','人物裁剪最小宽度。','拒绝更多边缘截断/过窄人物。','允许窄框，侧身也更容易通过，但信息较少。',[24,64])
    knob(group,'min_blur',blur,0.0,10000.0,'Laplacian方差','清晰度下限，越大通常越清晰；不是概率，受纹理/分辨率影响。','只接收较清晰画面，候选/匹配次数可能不足。','允许模糊画面，匹配误差可能增加。',[10,80])

knob('a_quality','min_detection_conf',0.3,0.01,1,'0～1','进入人物档案的检测置信度门槛，独立于跟踪门槛。','档案筛选更严，误框少，但可用图可能减少。','允许更多弱检测人物截图。',[0.25,0.6])
knob('a_quality','joint_conf',0.35,0.01,1,'0～1','判断肩/髋躯干关节可用的置信度门槛。','可用关节减少，人物完整度检查更严。','更多关节可用，错误姿态可能被接收。',[0.3,0.6])
knob('a_quality','minimum_torso_joints',2,0,4,'个','四个肩/髋关节中至少可见的个数。','倾向躯干完整的人，遮挡者可能无图。','允许更多局部身体，外观证据较少。',[2,4])
knob('a_quality','max_overlap',0.35,0.0,1.0,'面积比例','其他人物框与当前人物框的最大交叠面积/当前框面积；只是遮挡近似。','允许更拥挤画面，截图混入他人风险增加。','更严格排除重叠，拥挤场景候选减少。',[0.15,0.4])
knob('a_quality','minimum_selection_quality',0.4,0.0,1.0,'综合评分','截图综合质量下限，结合清晰度、尺寸、置信度、重叠与截断。','拒绝边缘/模糊/小人物图，可能不足三张。','保留更多差图，选帧可用率提高但质量下降。',[0.35,0.6])
knob('a_quality','blur_saturation',150.0,1.0,10000.0,'Laplacian方差','清晰度分量达到满分的方差值。','同一图片的清晰度分数降低，更看重高纹理清晰图。','更早达到满分，清晰度的区分减弱。',[80,300])
knob('a_quality','size_saturation',0.6,0.1,1.0,'图高比例','人物达到满尺寸分量时的人物高度/源图高度。','同一人物尺寸评分降低，更倾向大人物图。','较小人物更早获得满尺寸分。',[0.4,0.8])
knob('a_quality','edge_penalty',0.65,0.0,1.0,'乘数','人物框贴近画面边缘时乘以该值；越小惩罚越强。','更容易保留边缘截断的人物。','更排斥边缘截断，快速经过者可能不足三张。',[0.5,0.85])

knob('a_keyframes','minimum',3,3,5,'张','每条合格轨迹至少保存的截图数量，不能用重复图凑数。','需要更多证据，短轨迹可能被拒绝。','允许较少证据，最低固定为三张。',[3,3])
knob('a_keyframes','maximum',5,3,5,'张','单次合格轨迹最多保存的代表图，严格上限五张。','增加视角/时间覆盖，同时增加保存图片。','截图更精简，但覆盖减少。',[3,5])
knob('a_keyframes','min_gap_ms',200,50,5000,'ms','选中截图之间的最小源时间间隔。','减少相邻重复图，但短轨迹可能不足三张。','更易凑足截图，但内容可能相近。',[150,500])
knob('a_keyframes','candidate_bin_ms',200,50,5000,'ms','候选缓存时间分桶，每桶只保留质量最高的图。','候选更稀，内存较少，短动作可能遗漏。','候选更密，内存和比较开销增加。',[100,400])
knob('a_keyframes','candidate_limit',48,5,200,'张/轨迹','只在内存中缓存的候选上限，不代表最终保存数量。','长轨迹可考虑更多候选，内存开销增加。','内存更少，可能错过好角度。',[24,64])
knob('a_keyframes','consistency_similarity',0.55,-1.0,1.0,'余弦相似度','候选与轨迹外观主体一致的最低相似度。','更严防串人，角度变化大的人可能被拒绝。','兼容更多角度，但串人候选更易混入。',[0.5,0.7])
knob('a_keyframes','consistency_fraction',0.6,0.5,1.0,'比例','候选中与外观主体一致的最小比例。','对轨迹外观一致性更严格。','容忍更多异常候选，串人风险增加。',[0.6,0.8])
knob('a_keyframes','quality_weight',0.75,0.01,1.0,'权重','选帧排序的质量权重，三个权重按总和归一化。','更偏向清晰、完整、遮挡少的图。','相对更重视外观差异和时间覆盖。',[0.6,0.9])
knob('a_keyframes','diversity_weight',0.15,0.0,1.0,'权重','外观差异权重，不保证一定是正面/侧面/背面。','更多不同外观，可能牺牲部分清晰度。','更倾向同角度的清晰图。',[0.05,0.25])
knob('a_keyframes','time_weight',0.1,0.0,1.0,'权重','时间覆盖权重，三个权重按总和归一化。','选择时间更分散，可能带入边缘图。','更集中在最清晰的时段。',[0.05,0.2])

knob('archive_matching','reuse_threshold',0.75,-1.0,1.0,'余弦相似度','A复用旧人物编号的多帧相似度中位数门槛。','更难复用，重复档案更多，误合并较少。','更容易复用，误合并风险增加。',[0.7,0.85])
knob('archive_matching','reuse_margin',0.1,0.0,2.0,'相似度差','A第一、第二候选之间的最小分差。','更难在相似衣着人物间做决定，新增档案可能增多。','更易复用，但相似人物容易混淆。',[0.08,0.2])
knob('archive_matching','frame_threshold',0.7,-1.0,1.0,'余弦相似度','A复用编号时每张支持图的最低相似度。','每帧支持更严格。','单帧支持更宽松，偶然匹配可能增多。',[0.65,0.8])
knob('archive_matching','minimum_support',3,3,5,'张','A复用编号至少需要的支持截图数量。','合并更稳但更难复用。','更容易复用，但最低仍需三张。',[3,4])
knob('archive_matching','possible_duplicate_threshold',0.6,-1.0,1.0,'余弦相似度','不足以复用但仍标记可能重复的相似度下限。','可能重复提示减少。','产生更多提示，人工检查负担增加。',[0.55,0.7])
knob('archive_matching','simultaneous_overlap_ms',100,0,2000,'ms','同次A运行两轨迹重叠超过此值时禁止复用同编号。','容忍更长重叠，重复检测易合并，也可能合并不同人。','更严拒绝并行轨迹复用，重复档案可能增加。',[50,200])
knob('archive_matching','maximum_templates',5,3,5,'张/人物','持久人物档案的有效代表图上限，旧超额图进入本人history。','更多外观覆盖，但图片和匹配开销增加。','更精简，视角覆盖可能下降。',[3,5])
knob('archive_matching','top_k_templates',3,1,5,'张','A计算与某档案的单帧相似度时，取最相似的K张代表图求平均。','多张共同支持，更保守，但角度差异大的图可能拉低分数。','更偏向最佳单张，角度适应更强，偶然误配也更容易。',[2,3])

knob('b_matching','feature_fps',4,1,30,'fps/人','B每人OSNet外观更新最高频率，实际受源帧率限制。','更早累积匹配支持，特征计算更多。','减少计算，但短交互可能支持不足。',[3,8])
knob('b_matching','threshold',0.65,-1.0,1.0,'余弦相似度','B首次建立档案关联的最低分数。','未知人物更多，误关联通常更少。','更容易关联，但误关联风险增加。',[0.6,0.8])
knob('b_matching','margin',0.08,0.0,2.0,'相似度差','第一/第二档案及竞争人物之间的最小分差。','更排斥相似衣着的模糊匹配。','更多关联，串人风险增加。',[0.05,0.15])
knob('b_matching','confirmations',3,1,20,'次特征更新','首次关联需要连续支持的独立时间更新数。','关联更慢，偶然误配较少。','更快关联，更容易受单帧噪声影响。',[3,5])
knob('b_matching','retain_threshold',0.55,-1.0,1.0,'余弦相似度','已关联轨迹的最低保留门槛，不能高于首次关联门槛。','角度改变时更容易解除关联。','更耐受角度变化，但可能延续错误关联。',[0.5,0.65])
knob('b_matching','max_support_gap_ms',1000,50,10000,'ms','确认关联时相邻支持特征的最大间隔。','允许更稀疏支持，也可能混合相隔较久的证据。','要求支持更连续，低频提特征时可能无法确认。',[500,1500])
knob('b_matching','reservation_ms',800,0,10000,'ms','人物暂时消失后，其档案编号仍被保留占用的时间。','更耐受遮挡，但其他轨迹复用编号更慢。','更快释放编号，遮挡时更容易碎片化。',[500,1500])
knob('b_matching','contradiction_limit',3,1,20,'次特征更新','连续多少次特征矛盾后解除已有关联。','更稳定，但错误关联解除更慢。','更快纠错，也更容易因噪声反复解除。',[2,5])
knob('b_session','minimum_support',3,3,30,'次','公开交互事件至少需要的已匹配特征观测数。','事件归属更严格，短交互易未知。','更易给出人物归属，最低仍为三次。',[3,5])
knob('b_session','minimum_coverage',0.6,0.0,1.0,'比例','会话有效特征观测中，已关联同一G编号的最小比例。','归属更严格，未知更多。','可以接受较晚的关联，但证据薄弱事件可能被归属。',[0.5,0.8])
knob('b_matching','top_k_templates',3,1,5,'张','B外观匹配取每个人物档案中最相似的K张代表图平均。','需要多张图共同支持，孤立相似图影响减小。','更依赖最相似的图，可能改善角度适应，也可能增加误配。',[2,3])

for name,default,low,high,unit,meaning,up,down in [
    ('wrist_confidence',.35,.01,1.,'0～1','触发货架候选的腕点置信度下限。','拒绝更多弱腕点，误触发减少但可能漏交互。','接受更多弱腕点，抖动/误触发可能增加。'),
    ('enter_ms',300,0,5000,'ms','腕点持续靠近货架多久才启动候选。','更少短暂误触发，短操作可能漏掉。','更快触发，但路过/短暂摆手可能产生候选。'),
    ('max_start_gap_ms',150,20,2000,'ms','启动阶段相邻有效腕点观测的最大间隔。','允许姿态断续，也可能把不连续动作合并。','连续性更严格，低帧率时可能难触发。'),
    ('same_visit_gap_ms',5000,100,30000,'ms','候选内两次货架操作允许的最长静默间隔。','更多操作合并为一段，五帧可能覆盖不全。','更多独立会话及API请求，长动作可能被切碎。'),
    ('departure_ms',1000,0,10000,'ms','人物可见且远离货架持续多久后关闭会话。','更迟关闭，减少短暂转身切段。','更早关闭，离开再伸手可能被分成两段。'),
    ('missing_ms',750,50,10000,'ms','人物或配准丢失多久后关闭会话。','容忍较长丢失，但边界不确定性增加。','快速结束，遮挡时更容易切碎会话。'),
    ('margin_px_at_720',10,0,100,'720宽像素','腕点在层区之外允许的距离，随视频宽度缩放。','扩大靠近范围，误触发可能增加。','更要求腕点进入标定区域，边界操作可能漏掉。'),
    ('minimum_reach_motion_px_at_720',5,0,100,'720宽像素','启动候选所需的稳定腕点位移，不是接触证明。','减少静止/抖动触发，小动作可能漏掉。','更容易触发，姿态噪声可能被当作伸手。'),
    ('minimum_observations',3,3,30,'帧','启动会话至少需要的有效腕点观测数。','要求更多连续证据，启动更慢。','启动更快，最低三帧。'),
    ('departure_distance_px_at_720',50,0,300,'720宽像素','判定人物远离货架的基础距离门槛。','需要离得更远才开始关闭计时。','更容易认定离开，短转身可能被切段。'),
    ('departure_body_width_ratio',.8,0.,3.,'人物框宽比例','远离距离还需超过人物宽度乘此值，与基础距离取较大。','近景大人物需走得更远才算离开。','更容易判定近景人物离开。')]:
    knob('interaction',name,default,low,high,unit,meaning,up,down)

for name,default,low,high,unit,meaning,up,down in [
    ('joint_confidence',.35,.01,1.,'0～1','计算估计手区所需的肘/腕置信度。','手区更可靠但可用轨迹减少。','更多手区，错误姿态影响增加。'),
    ('forearm_min_px_at_720',8,1,100,'720宽像素','肘到腕的最小长度。','拒绝过短前臂，远处人物可能失效。','允许小前臂，也更容易接受姿态噪声。'),
    ('forearm_max_body_ratio',.5,.1,1.,'人体高度比例','肘到腕长度不得大于人物高度乘此值。','容忍更长估计前臂，错误姿态可能通过。','严格排除异常长前臂，也可能拒绝真实透视姿态。'),
    ('extension_ratio',.4,0.,1.,'前臂长度比例','从腕点沿肘腕方向延长估计手的位置。','估计手更远，候选层号可能改变。','估计手更靠近腕点，不能解释为真实手掌测量。'),
    ('uncertainty_min_ratio',.25,0.,1.,'前臂长度比例','估计手不确定线段的近端延长比例。','线段近端外移，层号几何判断改变。','线段更靠近腕点，可能横跨层边界。'),
    ('uncertainty_max_ratio',.55,0.,1.5,'前臂长度比例','估计手不确定线段的远端延长比例。','线段更长，更多边界点弃权，层号更保守。','线段更短，层号更容易给出，也可能过于自信。')]:
    knob('hand_geometry',name,default,low,high,unit,meaning,up,down)

for name,default,low,high,unit,meaning,up,down in [
    ('minimum_samples',5,3,60,'帧','伸手/收手轨迹最少观测数。','轨迹证据更多，短动作可能回退均匀抽帧。','短动作更易识别转折，也更容易受抖动影响。'),
    ('onset_tolerance_ms',300,0,2000,'ms','首个可靠手区距交互起点的最大延迟。','允许较晚姿态出现，但可能遗漏首次伸手。','更严格要求完整开头，回退可能增加。'),
    ('smooth_window_ms',80,0,1000,'ms','手轨迹中位数平滑窗口的左右半宽。','更平滑，但短峰值可能被抹掉。','保留快速动作，姿态抖动更明显。'),
    ('search_window_ms',3000,100,15000,'ms','从首次手区观测起搜索首次伸收转折的最长时间。','可覆盖慢动作，也可能选到后续操作。','只找早期动作，慢动作可能回退。'),
    ('minimum_approach_px_at_720',15,1,200,'720宽像素','判断首次伸手的最小前进位移。','减少抖动峰值，小动作可能失效。','更易找到峰值，抖动误判可能增加。'),
    ('minimum_approach_ms',150,0,3000,'ms','伸手前进阶段最短时长。','拒绝短促变化，快速伸手可能遗漏。','允许更快动作，也更容易误认跳变。'),
    ('retreat_confirm_ms',200,0,3000,'ms','峰值后至少等待多久确认回撤。','转折更稳定，动作定位更晚。','更快确认，噪声转折可能增加。'),
    ('retreat_distance_px_at_720',12,1,200,'720宽像素','确认回撤所需的峰值到当前距离。','更强回撤才确认，小收手可能失效。','更易确认回撤，噪声影响增加。'),
    ('retreat_smooth_window_ms',120,0,1000,'ms','确认回撤时最近一段进度的中位数窗口。','要求更持续回撤，确认更迟。','对短回撤更敏感，也更易受抖动影响。'),
    ('retreat_median_px_at_720',10,0,200,'720宽像素','最近窗口中位进度需比峰值回落的距离。','持续回撤要求更高。','回撤要求放宽，可能误把抖动当收手。'),
    ('max_observation_gap_ms',150,20,2000,'ms','首次伸收轨迹内相邻有效手区最大间隔。','容忍缺失，轨迹可信度可能下降。','更严格连续，低帧率可能回退。'),
    ('withdrawal_window_ms',1600,100,10000,'ms','从峰值寻找明显收手帧的最长期限。','容忍慢收手，也可能选到后续动作。','更局限于首次动作，慢收手可能失效。'),
    ('withdrawal_min_ms',300,0,3000,'ms','收手关键帧至少比峰值晚多久。','前后状态变化通常更明显，短动作可能遗漏。','更靠近峰值，图片可能较相似。'),
    ('withdrawal_distance_px_at_720',18,1,300,'720宽像素','选择收手帧所需的基础回撤距离。','收手变化更明显，小动作难满足。','更容易选择收手帧，但状态变化可能不明显。'),
    ('withdrawal_amplitude_ratio',.3,0.,1.,'伸手幅度比例','回撤距离还需达到总伸手幅度乘此值，与基础距离取大。','要求更充分收手。','允许较小收手，前后状态区分减少。'),
    ('early_hand_tolerance_ms',500,0,3000,'ms','两只手峰值相差不超过此值时择优选主操作手。','较晚的另一只手也可竞争，可能偏离最早操作。','更优先最早伸手的手臂。')]:
    knob('reach',name,default,low,high,unit,meaning,up,down)
knob('layer_vote','window_ms',120,0,2000,'ms','只在首次伸手峰值左右此窗口投票操作来源层。','投票帧更多，也可能混入持有或后续动作。','定位更集中，但支持帧可能不足。')
knob('layer_vote','minimum_support',3,1,60,'帧','给出算法候选层至少需要的同层支持帧数。','层号更保守，未知更多。','更容易给出层号，单帧噪声影响增加。')
knob('layer_vote','minimum_fraction',.7,0.,1.,'比例','同层支持数占可用估计手区观测的最低比例。','更要求一致，未知更多。','更多候选层，但边界/跨层误判可能增加。')
knob('evidence','before_ms',300,0,5000,'ms','交互开始前关键帧的提前量，超出源视频会明确标为截断。','上下文更早，可能离动作太远。','更靠近动作，操作前原始状态可能看不全。')
knob('evidence','after_ms',300,0,5000,'ms','交互结束后关键帧的延后量。','更可能看到稳定后状态，也可能人物已离开。','更靠近结束，结果状态可能尚未稳定。')
for name,default,low,high,meaning,up,down in [
    ('min_inliers',24,4,500,'货架配准至少需要的几何内点数。','校验更严，纹理少/遮挡时更容易失效。','更易通过，错配风险增加。'),
    ('min_ratio',.3,.01,1.,'几何内点占匹配点的最低比例。','配准更严格，失效帧增多。','接受更多有噪声的匹配。'),
    ('orb_features',1800,100,10000,'ORB目标特征点数量。','可能提高匹配覆盖，但CPU耗时增加。','更快，低纹理场景匹配可能不足。'),
    ('match_ratio',.72,.1,.99,'描述子最近/次近距离比值门槛。','更多匹配通过，也增加歧义。','匹配更严格，数量可能不足。'),
    ('ransac_px',3.5,.1,20.,'RANSAC重投影误差容忍像素。','允许更大误差，配准可能不准。','几何更严格，噪声下可能失败。'),
    ('min_area_ratio',.55,.1,.99,'映射后画面面积比下限。','拒绝更小缩放，视角变化兼容减少。','容忍更大缩小，也可能接收异常变换。'),
    ('max_area_ratio',1.8,1.01,5.,'映射后画面面积比上限。','容忍更大放大，也可能接收异常变换。','更严格拒绝放大。'),
    ('max_shift_ratio',.23,.01,1.,'平均角点位移/图像最长边上限。','容忍机位变化更大，错配风险增加。','更严格检查同一机位，小抖动可能失效。')]:
    knob('registration',name,default,low,high,'内点/比例/像素（见含义）',meaning,up,down)
knob('vlm','timeout_s',180,10,600,'s','单次多模态HTTP请求超时。','允许慢响应，但失败等待更长。','更早报超时；超时不代表服务端未计费。',[60,180])
knob('vlm','max_tokens',2000,256,8000,'token','模型输出长度上限，不是输入图片数量。','更少截断，但最大延迟/费用可能增加。','输出更短，JSON可能被截断并判复核失败。',[1500,3000])


def defaults():
    result={g:{k:v['default'] for k,v in specs.items()} for g,specs in SPECS.items()}
    result['osnet']={'weights':'models/reid/osnet_x0_5_msmt17.pth'}
    result['vlm'].update(model='hackathon/v_model/qwen-max',base_url='https://ai-router-cn-pub.anker-in.com')
    return result


def documented_config():
    result=defaults();result['_help']=copy.deepcopy(SPECS)
    result['_help']['osnet']={'weights':{'meaning':'OSNet x0.5 MSMT17权重路径；架构和512维预处理固定，不能直接替换成x1.0等其他架构。',
        'range':'有效的同架构本地.pth文件','increase':'不适用，路径不是数值。','decrease':'不适用，路径不是数值。',
        'warning':'换权重必须重新提取人物档案特征，旧权重哈希不一致时程序拒绝混用。'}}
    result['_note']='只编辑各节数值；_help为说明。余弦相似度不是身份概率；范围是程序接受范围，recommended是初始调参建议。3～5张是有效档案代表图上限，历史旧图不删除。'
    result['_help']['vlm'].update(model={'meaning':'实际API模型ID；需由当前服务支持。','range':'非空、服务支持的模型ID','increase':'不适用，字符串。','decrease':'不适用，字符串。','unit':'模型ID'},
        base_url={'meaning':'OpenAI兼容HTTPS根地址，不含/chat/completions，不得含密钥。','range':'有效HTTPS地址，无嵌入凭证或查询参数','increase':'不适用，字符串。','decrease':'不适用，字符串。','unit':'URL'})
    result['_note']+=' 主入口A/B和货架交互读取本文件；货架多边形仍在单独标定JSON。五帧数量、每交互最多一次POST、未知保留null和六字段契约不是调参开关。'
    return result


def validate_parameters(value):
    if not isinstance(value,dict):raise ValueError('人物参数必须是JSON对象')
    result=defaults()
    for group,settings in value.items():
        if group.startswith('_'):continue
        if group not in result or not isinstance(settings,dict):raise ValueError(f'未知人物参数节: {group}')
        for key,v in settings.items():
            if key not in result[group]:raise ValueError(f'未知人物参数: {group}.{key}')
            if group=='osnet' or (group=='vlm' and key in ('model','base_url')):
                if not isinstance(v,str) or not v:raise ValueError('osnet.weights必须是有效路径')
            else:
                spec=SPECS[group][key];low,high=spec['range']
                if isinstance(v,bool) or not isinstance(v,(int,float)) or not low<=v<=high:
                    raise ValueError(f'{group}.{key}范围为{low}～{high}')
                if spec['type']=='integer' and type(v) is not int:raise ValueError(f'{group}.{key}必须为整数')
            result[group][key]=v
    if result['a_video']['feature_fps']>result['a_video']['sampling_fps']:raise ValueError('A feature_fps不能大于sampling_fps')
    for g in ('a_tracker','b_tracker'):
        if result[g]['imgsz']%32:raise ValueError(f'{g}.imgsz必须是32的整数倍')
        if result[g]['track_low_thresh']>=result[g]['track_high_thresh']:raise ValueError(f'{g}: low必须小于high')
    if result['a_keyframes']['minimum']>result['a_keyframes']['maximum']:raise ValueError('关键帧minimum不能大于maximum')
    if result['a_keyframes']['maximum']>result['archive_matching']['maximum_templates']:raise ValueError('单次maximum不能大于档案maximum_templates')
    if result['archive_matching']['minimum_support']>result['a_keyframes']['maximum']:raise ValueError('复用minimum_support不能大于选图maximum')
    if result['b_matching']['retain_threshold']>result['b_matching']['threshold']:raise ValueError('retain_threshold不能大于threshold')
    hand=result['hand_geometry']
    if not hand['uncertainty_min_ratio']<=hand['extension_ratio']<=hand['uncertainty_max_ratio']:raise ValueError('手区extension_ratio必须在不确定线段范围内')
    if result['reach']['withdrawal_min_ms']>result['reach']['withdrawal_window_ms']:raise ValueError('收手最短等待不能超过收手搜索窗口')
    api=urlparse(result['vlm']['base_url'])
    if api.scheme!='https' or not api.netloc or api.username or api.password or api.query or api.fragment:
        raise ValueError('vlm.base_url必须是无内嵌凭证的HTTPS根地址')
    return result


def load_parameters(root,path=DEFAULT_CONFIG):
    target=(Path(root)/path).resolve()
    return validate_parameters(json.loads(target.read_text(encoding='utf-8-sig')))
