import { Addon, Cell, CellView, Graph, JQuery, Node, Point } from '@antv/x6';
// @ts-ignore
import listen from 'good-listener';
import { INodeData } from '../model/data';
import { PORTS, SHAPE_SIZE, SHAPE_TEXT_MAX_HEIGHT } from '../shape/gengral-config';
import nodeWarningImg from '../svgs/node-warning.svg';
import { WorkflowValidator } from './workflow-validator';

const { width, height } = SHAPE_SIZE;

interface IDraggingListener {
  mousePosition: Point.PointLike;
  listener?: any;
}

export default class WorkflowDnd {
  private readonly graph: Graph;
  private readonly dnd: Addon.Dnd;
  private readonly draggingListener: IDraggingListener = {
    mousePosition: { x: -1, y: -1 },
  }

  constructor(graph: Graph,
    workflowValidator: WorkflowValidator,
    nodeContainer: HTMLElement,
    clickNodeWarningCallback: (data: INodeData) => void) {
    this.graph = graph;
    this.dnd = new Addon.Dnd({
      target: graph,
      animation: true,
      getDragNode: (sourceNode: Node) => {
        const { width, height } = sourceNode.getSize();
        sourceNode.resize(width, height + SHAPE_TEXT_MAX_HEIGHT);

        // 开始拖拽时初始化的节点，直接使用，无需克隆
        return sourceNode;
      },
      getDropNode: (draggingNode: Node) => {
        const { width, height } = draggingNode.getSize();
        draggingNode.resize(width, height - SHAPE_TEXT_MAX_HEIGHT);

        // 结束拖拽时，必须克隆拖动的节点，因为拖动的节点和目标节点不在一个画布
        const targetNode = draggingNode.clone();
        setTimeout(() => {
          // 保证不偏移
          const { x, y } = targetNode.getPosition();
          targetNode.setPosition(x, y - SHAPE_TEXT_MAX_HEIGHT / 2);
        });

        // TODO 未填写必填参数时显示警告
        targetNode.addTools({
          name: 'button',
          args: {
            markup: [
              {
                tagName: 'image',
                attrs: {
                  width: 24,
                  height: 24,
                  'xlink:href': nodeWarningImg,
                  cursor: 'pointer',
                },
              },
            ],
            x: '100%',
            y: 0,
            offset: { x: -16, y: 0 },
            onClick: ({ cell: { data } }: { e: JQuery.MouseDownEvent, cell: Cell, view: CellView }) => {
              clickNodeWarningCallback(data);
            },
          },
        });

        return targetNode;
      },
      validateNode: (droppingNode: Node) => {
        const { mousePosition } = this.draggingListener;
        // 销毁监听器，必须先获取鼠标位置后销毁
        this.destroyListener();

        const nodePanelRect = nodeContainer.getBoundingClientRect();

        return workflowValidator.checkDroppingNode(droppingNode, mousePosition, nodePanelRect);
      },
    });
  }

  drag(data: INodeData, event: MouseEvent) {
    // 构建监听器
    this.buildListener();

    const node = this.graph.createNode({
      shape: 'vue-shape',
      width,
      height,
      component: 'custom-vue-shape',
      data: {
        ...data,
      },
      ports: { ...PORTS },
    });

    this.dnd.start(node, event);
  }

  private buildListener() {
    this.draggingListener.listener = listen(document.body, 'mousemove', (e: MouseEvent) => {
      this.draggingListener.mousePosition.x = e.x;
      this.draggingListener.mousePosition.y = e.y;
    });
  }

  private destroyListener() {
    if (this.draggingListener.listener) {
      this.draggingListener.listener.destroy();
    }

    this.draggingListener.mousePosition = { x: -1, y: -1 };
    delete this.draggingListener.listener;
  }
}