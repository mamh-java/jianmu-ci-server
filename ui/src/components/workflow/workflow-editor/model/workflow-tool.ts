import { Graph } from '@antv/x6';
import { ZoomTypeEnum } from './data/enumeration';
import { NODE } from '../shape/gengral-config';

const { selectedBorderWidth } = NODE;

const MIN_ZOOM = 20;
const MAX_ZOOM = 500;
// 缩放间隔
const ZOOM_INTERVAL = 10;

export class WorkflowTool {
  private readonly graph: Graph;

  constructor(graph: Graph) {
    this.graph = graph;
  }

  /**
   * 缩放
   * @param type
   */
  zoom(type: ZoomTypeEnum) {
    // 四舍五入保证与页面上的显示一致
    let factor = Math.round(this.graph.zoom() * 100);
    const remainder = factor % ZOOM_INTERVAL;
    factor -= remainder;

    switch (type) {
      case ZoomTypeEnum.IN:
        factor += ZOOM_INTERVAL;
        factor = factor > MAX_ZOOM ? MAX_ZOOM : factor;
        break;
      case ZoomTypeEnum.OUT:
        factor -= ZOOM_INTERVAL;
        factor = factor < MIN_ZOOM ? MIN_ZOOM : factor;
        if (remainder > 0) {
          factor += ZOOM_INTERVAL;
        }
        break;
      case ZoomTypeEnum.CENTER:
        // 缩放画布内容，使画布(graph)内容充满视口
        this.graph.zoomToFit();

        // 四舍五入保证与页面上的显示一致
        factor = Math.round(this.graph.zoom() * 100);
        if (factor > 100) {
          // 大于100%场景
          factor = 100;
        } else if (factor < MIN_ZOOM) {
          // 小于最小场景
          factor = MIN_ZOOM;
        } else {
          this.optimizeSelectionBoxStyle();
          return;
        }
        break;
      case ZoomTypeEnum.FIT:
        // 缩放画布内容，使画布(graph)内容充满视口
        this.graph.zoomToFit();

        // 四舍五入保证与页面上的显示一致
        factor = Math.round(this.graph.zoom() * 100);
        if (factor < MIN_ZOOM) {
          factor = MIN_ZOOM;
        } else if (factor > MAX_ZOOM) {
          factor = MAX_ZOOM;
        } else {
          this.optimizeSelectionBoxStyle();
          return;
        }
        break;
      case ZoomTypeEnum.ORIGINAL:
        // 将画布内容中心与视口中心对齐
        this.graph.centerContent();
        factor = 100;
        break;
    }

    const { x, y, width, height } = this.graph.getContentBBox();

    this.graph.zoomTo(factor / 100, {
      center: {
        x: width / 2 + x,
        y: height / 2 + y,
      },
    });

    this.optimizeSelectionBoxStyle();
  }

  optimizeSelectionBoxStyle(): void {
    const factor = this.graph.zoom();

    Array.from(this.graph.container.querySelectorAll<SVGElement>('.x6-widget-transform'))
      .forEach(el => {
        const t = selectedBorderWidth * factor;
        el.style.borderWidth = `${t}px`;
        el.style.marginLeft = `-${t}px`;
        el.style.marginTop = `-${t}px`;
      });
  }
}