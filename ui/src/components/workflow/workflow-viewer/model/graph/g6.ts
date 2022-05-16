import { BaseGraph } from '../base-graph';
import { G6Event, Graph, IBBox, IG6GraphEvent, Item, LayoutConfig, NodeConfig, INode } from '@antv/g6';
import { DslTypeEnum, TaskStatusEnum, TriggerTypeEnum } from '@/api/dto/enumeration';
import { INodeDefVo } from '@/api/dto/project';
import { parse } from '../../utils/dsl';
import { ITaskExecutionRecordVo } from '@/api/dto/workflow-execution-record';
import { GraphDirectionEnum, NodeTypeEnum } from '../data/enumeration';
import { sortTasks } from '../util';
import { size } from '../../shapes/async-task';
import { INodeMouseoverEvent } from '../data/common';

/**
 * 计算布局配置
 * @param dslType
 * @param nodes
 * @param direction
 */
function calculateLayout(dslType: DslTypeEnum, nodes: NodeConfig[], direction: GraphDirectionEnum): LayoutConfig {
  const rankdir = direction === GraphDirectionEnum.HORIZONTAL ? 'LR' : 'TB';

  if (dslType === DslTypeEnum.WORKFLOW || nodes.length < 8) {
    return {
      type: 'dagre',
      rankdir,
      // 节点间距（px）。在rankdir 为 'TB' 或 'BT' 时是节点的水平间距；在rankdir 为 'LR' 或 'RL' 时代表节点的竖直方向间距
      nodesep: rankdir === 'TB' ? 60 : 35,
      // 层间距（px）。在rankdir 为 'TB' 或 'BT' 时是竖直方向相邻层间距；在rankdir 为 'LR' 或 'RL' 时代表水平方向相邻层间距
      ranksep: 70,
      // 是否保留布局连线的控制点，默认false
      controlPoints: true,
    };
  }

  let cols = 3;
  if (nodes.length < 13) {
    cols = 3;
  } else if (nodes.length < 28) {
    cols = 5;
  } else {
    cols = 10;
  }
  let rows = Math.ceil(nodes.length / cols);

  if (rows % 2 === 0) {
    // 偶数行数时，强制转为奇数行数
    rows--;
    cols = Math.ceil(nodes.length / rows);
  }

  const arrList = nodes.split(cols);

  arrList.forEach((arr, iIndex) =>
    (iIndex % 2 === 0 ? arr : arr.reverse())
      .forEach((item, jIndex) => (item.degree = -1 * (iIndex * cols + jIndex))));

  return {
    type: 'grid',
    // 是否防止重叠，必须配合下面属性 nodeSize，只有设置了与当前图节点大小相同的 nodeSize 值，才能够进行节点重叠的碰撞检测
    preventOverlap: true,
    // 节点大小（直径）。用于防止节点重叠时的碰撞检测
    nodeSize: 60,
    // 避免重叠时节点的间距 padding。preventOverlap 为 true 时生效
    preventOverlapPadding: 130,
    cols,
    rows,
  };
}

export class G6Graph extends BaseGraph {
  private readonly graph: Graph;

  constructor(dsl: string, triggerType: TriggerTypeEnum, nodeInfos: INodeDefVo[],
    container: HTMLElement, direction: GraphDirectionEnum) {
    const { dslType, nodes, edges } = parse(dsl, triggerType, nodeInfos);
    super(dslType);

    const parentElement = container.parentElement as HTMLElement;
    this.graph = new Graph({
      modes: {
        default: [
          // 画布行为
          'drag-canvas',
          // 'scroll-canvas',
          // 'zoom-canvas',

          // 快捷键行为
          'shortcuts-call',
        ],
      },
      // 指定挂载容器
      container,
      // 图的宽度
      width: parentElement.clientWidth,
      // 图的高度
      height: parentElement.clientHeight,
      layout: calculateLayout(dslType, nodes, direction),
    });

    // 加载数据
    this.graph.data({
      // 节点集
      nodes,
      // 边集
      edges,
    });

    container.style.visibility = 'hidden';

    // 渲染
    this.graph.render();

    setTimeout(() => {
      this.fitCanvas();

      container.style.visibility = '';
    });
  }

  getNodes(): INode[] {
    return this.graph.getNodes();
  }

  getDirection(): GraphDirectionEnum {
    if (this.graph.get('layout').rankdir === 'LR') {
      return GraphDirectionEnum.HORIZONTAL;
    }

    return GraphDirectionEnum.VERTICAL;
  }

  configNodeAction(mouseoverNode: ((evt: INodeMouseoverEvent) => void)): void {
    // 设置鼠标滑过事件
    this.graph.on(G6Event.NODE_MOUSEOVER, (ev: IG6GraphEvent) => {
      const node = ev.item as Item;
      const model = node.getModel();

      if (model.type === NodeTypeEnum.FLOW_NODE) {
        // flow node时，忽略
        return;
      }

      if (ev.shape.get('name').includes('animate_')) {
        // 非异步任务或滑过动画相关shape时，忽略
        return;
      }

      const zoom = this.graph.getZoom();
      const width = size.width * zoom;
      const height = size.height * zoom;

      const { x, y } = this.graph.getClientByPoint(model.x as number, model.y as number);

      mouseoverNode({
        id: node.getID(),
        description: (model.description || '') as string,
        type: model.type as NodeTypeEnum,
        width,
        height,
        x,
        y,
      });
    });
  }

  changeSize(width: number, height: number) {
    this.graph.changeSize(width, height);
  }

  destroy() {
    this.graph.destroy();
  }

  zoomTo(factor: number): void {
    this.graph.zoomTo(factor / 100, this.graph.getGraphCenterPoint());
  }

  getZoom(): number {
    return this.graph.getZoom();
  }

  /**
   * 适配到画布
   */
  fitCanvas(): void {
    // 判断原始大小（100%）是否溢出
    if (!this.checkContentOverflow()) {
      // 对齐到画布中心
      this.graph.fitCenter();
      return;
    }

    const minRatio = this.zoom.min / 100;

    // 缩放到最小，判断是否溢出
    if (this.checkContentOverflow(minRatio)) {
      // 对齐到画布中心
      this.graph.fitCenter();

      // 溢出时，缩放到最小
      this.graph.zoomTo(minRatio, this.graph.getGraphCenterPoint());
      return;
    }

    // 没有溢出时，适配到画布中
    this.graph.fitView();
  }

  /**
   * 适配到视图
   */
  fitView(): void {
    // 判断原始大小（100%）是否溢出
    if (!this.checkContentOverflow()) {
      // 适配到画布中
      this.graph.fitView();
      return;
    }

    const minRatio = super.zoom.min / 100;

    // 缩放到最小，判断是否溢出
    if (this.checkContentOverflow(minRatio)) {
      // 对齐到画布中心
      this.graph.fitCenter();

      // 溢出时，缩放到最小
      this.graph.zoomTo(minRatio, this.graph.getGraphCenterPoint());
      return;
    }

    // 没有溢出时，适配到画布中
    this.graph.fitView();
  }

  /**
   * 更新节点状态
   * @param tasks
   */
  updateNodeStates(tasks: ITaskExecutionRecordVo[]): void {
    this.graph.getNodes()
      .filter(node => node.getModel().type === NodeTypeEnum.ASYNC_TASK)
      .forEach(node => {
        const task = sortTasks(tasks, true, node.getID())[0];
        const status = task ? task.status : TaskStatusEnum.INIT;

        this.graph.setItemState(node, 'status', status);
      });

    this.graph.getEdges().forEach(edge => {
      let status = edge.getSource().getStates().find(status => status === `status:${TaskStatusEnum.RUNNING}`);
      if (!status) {
        status = edge.getTarget().getStates().find(status => status === `status:${TaskStatusEnum.RUNNING}`);
      }

      this.graph.setItemState(edge, 'running', !!status);
    });
  }

  /**
   * 高亮节点状态
   * @param status
   * @param active
   */
  highlightNodeState(status: TaskStatusEnum, active: boolean): void {
    this.graph.set('HIGHLIGHT_STATUS', active ? status : undefined);

    this.graph.getNodes()
      .filter(node =>
        node.getModel().type === NodeTypeEnum.ASYNC_TASK &&
        node.getStates().includes(`status:${status}`),
      )
      .forEach(node => {
        this.graph.setItemState(node, 'highlight', active);
      });
  }

  /**
   * 刷新节点状态高亮
   * @param status
   */
  refreshNodeStateHighlight(status: TaskStatusEnum): void {
    const highlightStatus = this.graph.get('HIGHLIGHT_STATUS');

    if (!highlightStatus) {
      return;
    }

    if (highlightStatus !== status) {
      // 关灯
      this.highlightNodeState(status, false);
    }

    // 开灯
    this.highlightNodeState(highlightStatus, true);
  }

  /**
   * 检查内容是否溢出
   * @param ratio
   */
  private checkContentOverflow(ratio: number = 1): boolean {
    const bBoxes: IBBox[] = [];
    bBoxes.push(...this.graph.getNodes().map(node => node.getCanvasBBox()));
    bBoxes.push(...this.graph.getEdges().map(node => node.getCanvasBBox()));

    // 初始未溢出
    let tag = false;

    for (const { maxX, maxY } of bBoxes) {
      tag = maxX > this.graph.getWidth() / ratio || maxY > this.graph.getHeight() / ratio;

      if (tag) {
        break;
      }
    }

    return tag;
  }
}