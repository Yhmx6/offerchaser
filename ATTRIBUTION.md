# 开源与致谢

OfferChaser 的底子来自下面这个开源项目，不是它官方发布的版本。

## get_jobs

- 仓库：[https://github.com/loks666/get_jobs](https://github.com/loks666/get_jobs)
- 作者：loks666
- 描述：AI 找工作助手 / 多招聘平台自动投简历项目
- Copyright：**Copyright (c) 2024-2026 GET JOBS**
- 许可证：**PolyForm Noncommercial License 1.0.0**
  - 许可证全文：[LICENSE](LICENSE)
  - 官方说明：[https://polyformproject.org/licenses/noncommercial/1.0.0](https://polyformproject.org/licenses/noncommercial/1.0.0)
  - 上游许可证：[https://github.com/loks666/get_jobs/blob/main/LICENSE](https://github.com/loks666/get_jobs/blob/main/LICENSE)

### Required Notice

```text
Required Notice: Copyright (c) 2024-2026 GET JOBS
(https://github.com/loks666/get_jobs)
```

### 我做了什么

上游的骨架我留下了，但上面那层业务是我自己做的。一共 167 个类，118 个是新增的：规则引擎、AI 严格 JSON 校验、身份核对、发送确认、额度预留、审计台账、人工待办、模板、洞察、岗位解读、本机会话。

具体到类数：上游 88 个顶级类里，沿用 49 个（路径和类名没动，只换了包名）、删掉 39 个（51job、猎聘、智联三套平台实现，加上 `Bot`、`Platform` 和原入口）、新增 118 个。沿用的部分里 `Boss` 改动最大，从上游 1858 行的单个文件扩成了 10 个内部类型。

另外顺手清掉了上游默认配置里带的私人黑名单、个人介绍、个人招呼语和 AI 提示词。

逐文件清单、判定依据和合规自检项在 [doc/14-open-source-compliance.md](doc/14-open-source-compliance.md)。

### 授权

两边的著作权各归各的，商业使用得分别取得授权，谁也不能替对方点头：上游部分只能找 GET JOBS，我写的部分找我。

分发任何形式的副本都算分发，包括单独把 `OfferChaser.jar` 发出去。这种情况下 [LICENSE](LICENSE) 和 [NOTICE](NOTICE) 必须一起给，否则不满足 PolyForm 的 Notices 条款。

上游作者未参与、未审阅、未赞助，也没有背书 OfferChaser。碰到问题请提到 [OfferChaser 仓库](https://github.com/Yhmx6/OfferChaser)，不要当成上游 get_jobs 的 bug 去反馈。
