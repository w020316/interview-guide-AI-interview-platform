/**
 * 岗位选项列表
 *
 * 统一 ResumeView.vue 和 InterviewView.vue 中重复定义的 47 个 datalist option。
 * 模板用法：
 * <datalist id="job-suggestions">
 *   <option v-for="job in JOB_SUGGESTIONS" :key="job" :value="job" />
 * </datalist>
 */
export const JOB_SUGGESTIONS: readonly string[] = [
  'Java 后端开发工程师',
  '前端开发工程师',
  'Python 后端开发工程师',
  'Go 后端开发工程师',
  '全栈开发工程师',
  'iOS 开发工程师',
  'Android 开发工程师',
  '数据分析师',
  '算法工程师',
  '机器学习工程师',
  '产品经理',
  '项目经理',
  'UI/UX 设计师',
  '测试工程师',
  '运维工程师',
  'DevOps 工程师',
  '数据库管理员',
  '安全工程师',
  '教师',
  '医生',
  '护士',
  '药剂师',
  '律师',
  '会计师',
  '审计师',
  '财务经理',
  '销售经理',
  '市场专员',
  '运营专员',
  '人力资源专员',
  '行政助理',
  '翻译',
  '编辑',
  '记者',
  '建筑师',
  '土木工程师',
  '机械工程师',
  '电气工程师',
  '化工工程师',
  '供应链管理',
  '采购专员',
  '物流管理',
  '客户经理',
  '店长',
  '厨师',
  '摄影师',
]
