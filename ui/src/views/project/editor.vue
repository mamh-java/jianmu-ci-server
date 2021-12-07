<template>
  <div class="project-editor" v-loading="loading">
    <div class="right-top-btn">
      <router-link :to="{name: 'index'}">
        <jm-button class="jm-icon-button-cancel" size="small">取消</jm-button>
      </router-link>
      <jm-button
        v-if="source === 'processTemplates'"
        type="primary"
        class="jm-icon-button-previous"
        size="small"
        @click="previousStep"
      >上一步
      </jm-button>

      <jm-button type="primary" class="jm-icon-button-preserve" size="small"
                 @click="save" :loading="loading">保存
      </jm-button>
    </div>
    <jm-tabs v-model="activatedTab" class="tabs">
      <jm-tab-pane name="dsl" lazy>
        <template #label><i class="jm-icon-tab-dsl"></i> DSL模式</template>
        <div class="dsl-editor">
          <jm-dsl-editor v-model:value="editorForm.dslText"/>
        </div>
      </jm-tab-pane>
    </jm-tabs>
  </div>
</template>

<script lang="ts">
import { defineComponent, getCurrentInstance, inject, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { save } from '@/api/project';
import { ISaveForm } from '@/model/modules/project';
import { fetchProjectDetail, getProcessTemplate } from '@/api/view-no-auth';
import { IProcessTemplateVo } from '@/api/dto/project';

export default defineComponent({
  props: {
    id: String,
  },
  setup(props: any) {
    const { proxy } = getCurrentInstance() as any;
    const router = useRouter();
    const reloadMain = inject('reloadMain') as () => void;
    const route = useRoute();
    const editMode = !!props.id;
    const editorForm = ref<ISaveForm>({
      id: props.id,
      dslText: '',
    });
    const loading = ref<boolean>(false);

    if (route.query.templateId) {
      getProcessTemplate(route.query.templateId as unknown as number).then((res: IProcessTemplateVo) => {
        let dsl = res.dsl;
        if (route.query.processTemplatesName !== res.name) {
          let name = `name: ${res.name}`;
          editorForm.value.dslText = dsl.replace(name, `name: ${route.query.processTemplatesName}`);
        } else {
          editorForm.value.dslText = dsl;
        }
      }).catch((err: Error) => {
        console.warn(err.message);
      });
    } else if (route.query.source === 'processTemplates') {
      editorForm.value.dslText = `name: "${route.query.processTemplatesName || ''}"\n\nworkflow:\n`;
    }

    if (editMode) {
      loading.value = !loading.value;

      fetchProjectDetail(props.id).then(({ dslText }) => {
        editorForm.value.dslText = dslText;

        loading.value = !loading.value;
      }).catch((err: Error) => {
        loading.value = !loading.value;

        proxy.$throw(err, proxy);
      });
    }

    return {
      editMode,
      editorForm,
      loading,
      activatedTab: ref<string>('dsl'),
      source: route.query.source,
      previousStep: () => {
        router.push({
          name: 'process-template',
        });
      },
      save: () => {
        if (editorForm.value.dslText === '') {
          proxy.$error('DSL不能为空');

          return;
        }

        // 开启loading
        loading.value = true;

        save({ ...editorForm.value })
          .then(() => {
            if (!editMode) {
              // 刷新流程定义列表
              reloadMain();
            }

            proxy.$success(editMode ? '编辑成功' : '新增成功');

            router.push({ name: 'index' });
          })
          .catch((err: Error) => {
            // 关闭loading
            loading.value = false;

            proxy.$throw(err, proxy);
          });
      },
    };
  },
});
</script>

<style scoped lang="less">
.project-editor {
  font-size: 14px;
  color: #333333;
  margin-bottom: 25px;

  .right-top-btn {
    position: fixed;
    right: 20px;
    top: 78px;

    .jm-icon-button-back::before,
    .jm-icon-button-cancel::before,
    .jm-icon-button-preserve::before {
      font-weight: bold;
    }

    a {
      margin-right: 10px;
    }
  }

  .tabs {
    background-color: #FFFFFF;
    border-radius: 4px 4px 0 0;

    .dsl-editor {
      height: calc(100vh - 215px);

      > div {
        z-index: 1;
      }
    }
  }
}
</style>