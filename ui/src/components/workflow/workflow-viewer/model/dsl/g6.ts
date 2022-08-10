import { EdgeConfig, NodeConfig } from '@antv/g6';
import yaml from 'yaml';
import { NodeTypeEnum } from '../../model/data/enumeration';
import { DslTypeEnum, TriggerTypeEnum } from '@/api/dto/enumeration';
import { INodeDefVo } from '@/api/dto/project';
import shellIcon from '../../svgs/shape/shell.svg';
import { SHELL_NODE_TYPE } from '../../model/data/common';

/**
 * 节点标签最长长度
 */
export const MAX_LABEL_LENGTH = 10;

/**
 * 解析webhook节点
 * @param trigger
 * @param nodes
 * @param edges
 * @param isWorkflow
 */
function parseWebhook(trigger: any, nodes: NodeConfig[], edges: EdgeConfig[], isWorkflow: boolean): void {
  const { webhook } = trigger;

  const key = webhook ? webhook.split('@')[0] : 'webhook';
  const label = key;
  const type = NodeTypeEnum.WEBHOOK;

  nodes.splice(0, 0, {
    id: key,
    label,
    description: key,
    type,
    uniqueKey: webhook || undefined,
  });

  let startNode;

  if (isWorkflow) {
    startNode = nodes.find(item => item.type === NodeTypeEnum.START) as NodeConfig;
  } else {
    startNode = nodes[1];
  }

  edges.push({
    source: key,
    target: startNode.id,
    type: 'flow',
  });
}

/**
 * 解析cron节点
 * @param cron
 * @param nodes
 * @param edges
 * @param isWorkflow
 */
function parseCron(cron: string | undefined, nodes: NodeConfig[], edges: EdgeConfig[], isWorkflow: boolean): void {
  if (!cron) {
    // 不存在时，忽略
    return;
  }

  const key = NodeTypeEnum.CRON;
  // const label = cron.length > MAX_LABEL_LENGTH ? `${cron.substr(0, MAX_LABEL_LENGTH)}...` : cron;
  const label = 'cron';
  const description = cron;
  const type = NodeTypeEnum.CRON;

  nodes.splice(0, 0, {
    id: key,
    label,
    description,
    type,
  });

  let startNode;

  if (isWorkflow) {
    startNode = nodes.find(item => item.type === NodeTypeEnum.START) as NodeConfig;
  } else {
    startNode = nodes[1];
  }

  edges.push({
    source: key,
    target: startNode.id,
    type: 'flow',
  });
}

/**
 * 解析workflow节点
 * @param workflow
 */
function parseWorkflow(workflow: any): {
  nodes: NodeConfig[];
  edges: EdgeConfig[];
} {
  const nodes: NodeConfig[] = [];
  const edges: EdgeConfig[] = [];

  workflow.forEach(({ ref, name, task, image, needs }: any) => {

    let label = name || ref;
    label = label.length > MAX_LABEL_LENGTH ? `${label.substr(0, MAX_LABEL_LENGTH)}...` : label;
    const description = name || ref;
    let type = task;
    let uniqueKey;
    switch (task) {
      case NodeTypeEnum.START:
      case NodeTypeEnum.END:
        break;
      // case NodeTypeEnum.CONDITION:
      //   description += `<br/>${expression}`;
      //   break;
      default: {
        type = NodeTypeEnum.ASYNC_TASK;
        uniqueKey = image ? SHELL_NODE_TYPE : task;
        break;
      }
    }

    nodes.push({
      id: ref,
      label,
      description,
      type,
      uniqueKey,
    });

    needs?.forEach((need: string) => {
      edges.push({
        source: need,
        target: ref,
        type: 'flow',
      });
    });
  });

  // edges.forEach((edge, index, self) => {
  //   const { type, cases } = workflow.find(({ ref }: any) => ref === edge.source)!;
  //   // TODO 待扩展switch
  //   if (type !== NodeTypeEnum.CONDITION) {
  //     return;
  //   }
  //
  //   const kArr = Object.keys(cases);
  //   // 设置从条件网关出来的边内容
  //   for (const k of kArr) {
  //     const v = cases[k];
  //     // TODO 待扩展并发场景
  //     if (v === edge.target) {
  //       if (self.filter(({ source }) => source === edge.source).length === 1) {
  //         // 表示网关两条分支都连接到统一节点
  //         edge.label = kArr.join(' | ');
  //       } else {
  //         edge.label = k;
  //       }
  //
  //       break;
  //     }
  //   }
  // });

  // ====================================================================================================

  const groupByTarget: {
    [key: string]: EdgeConfig[];
  } = {};
  // 按target分组
  edges.forEach(edge => {
    const key = edge.target!;
    let arr = groupByTarget[key];
    if (!arr) {
      arr = [];
      groupByTarget[key] = arr;
    }
    arr.push(edge);
  });

  const flowNodeMap: {
    [flowNodeId: string]: {
      target: string;
      edges: EdgeConfig[];
    }[];
  } = {};
  Object.keys(groupByTarget).forEach(key => {
    const edges = groupByTarget[key];
    if (edges.length === 1) {
      return;
    }
    const flowNodeId = edges
      .map(edge => edge.source!)
      .sort((s1, s2) => s1.localeCompare(s2))
      .join('_');
    let list = flowNodeMap[flowNodeId];
    if (!list) {
      list = [];
      flowNodeMap[flowNodeId] = list;
    }
    list.push({
      target: key,
      edges,
    });
  });

  Object.keys(flowNodeMap).forEach(flowNodeId => {
    const flowNodes = flowNodeMap[flowNodeId];

    if (flowNodes.length === 1) {
      // 忽略
      return;
    }

    nodes.push({
      id: flowNodeId,
      type: NodeTypeEnum.FLOW_NODE,
    });

    flowNodes.forEach(({ target, edges: eArr }) => {
      // 删除交叉线
      eArr.forEach(e => edges.splice(edges.indexOf(e), 1));

      // 构建flow node相关线
      edges.push({
        source: flowNodeId,
        target,
        type: 'flow',
      });
    });

    // 构建flow node相关线
    flowNodes[0].edges.forEach(edge => {
      edges.push({
        ...edge,
        target: flowNodeId,
      });
    });
  });

  return { nodes, edges };
}

/**
 * 解析pipeline节点
 * @param pipeline
 */
function parsePipeline(pipeline: any): {
  nodes: NodeConfig[];
  edges: EdgeConfig[];
} {
  const nodes: NodeConfig[] = [];
  const edges: EdgeConfig[] = [];

  pipeline.forEach(({ ref, name, task, image }: any) => {
    if (nodes.length > 0) {
      edges.push({
        source: nodes[nodes.length - 1].id,
        target: ref,
        type: 'flow',
      });
    }

    let label = name || ref;
    label = label.length > MAX_LABEL_LENGTH ? `${label.substr(0, MAX_LABEL_LENGTH)}...` : label;

    nodes.push({
      id: ref,
      label,
      description: name || ref,
      type: NodeTypeEnum.ASYNC_TASK,
      uniqueKey: image ? SHELL_NODE_TYPE : task,
    });
  });

  return { nodes, edges };
}

/**
 * 解析yaml
 * @param dsl
 * @param triggerType
 * @param nodeInfos
 */
export function parse(dsl: string | undefined, triggerType: TriggerTypeEnum | undefined, nodeInfos?: INodeDefVo[]): {
  dslType: DslTypeEnum;
  nodes: NodeConfig[];
  edges: EdgeConfig[];
} {
  if (!dsl || !triggerType) {
    return { nodes: [], edges: [], dslType: DslTypeEnum.WORKFLOW };
  }

  const { trigger, workflow, pipeline } = yaml.parse(dsl);

  // 解析workflow节点
  const { nodes, edges } = workflow ? parseWorkflow(workflow) : parsePipeline(pipeline);

  switch (triggerType) {
    case TriggerTypeEnum.CRON:
      // 解析cron节点
      parseCron(trigger.schedule, nodes, edges, !!workflow);
      break;
    case TriggerTypeEnum.WEBHOOK:
      // 解析webhook节点
      parseWebhook(trigger, nodes, edges, !!workflow);
      break;
  }

  if (nodeInfos) {
    // 匹配icon
    nodes.forEach((node: NodeConfig) => {
      const { type, uniqueKey } = node;
      if (!uniqueKey) {
        return;
      }

      if (uniqueKey === SHELL_NODE_TYPE) {
        node.iconUrl = shellIcon;
        return;
      }

      node.iconUrl = nodeInfos.find(nodeInfo => {
        if (type === NodeTypeEnum.WEBHOOK) {
          return nodeInfo.webhook === uniqueKey;
        }

        if (type === NodeTypeEnum.ASYNC_TASK) {
          return nodeInfo.type === uniqueKey;
        }

        return false;
      })?.icon;
    });
  }

  return { nodes, edges, dslType: workflow ? DslTypeEnum.WORKFLOW : DslTypeEnum.PIPELINE };
}