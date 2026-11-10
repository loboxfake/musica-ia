# BARDO: autonomía de archivos del móvil y mantenimiento de skills

## Alcance implementado

BARDO administra archivos y carpetas únicamente dentro del árbol SAF que el usuario concede expresamente, inicialmente `Descargas/BARDO` y sus subcarpetas. El acceso usa el proveedor de documentos de Android; no solicita acceso global al almacenamiento ni inspecciona carpetas de otras aplicaciones.

Operaciones implementadas:
- listar hijos directos y leer texto UTF-8 por tramos acotados;
- crear archivos de texto y carpetas dentro del árbol;
- proponer el traslado a papelera mediante una cola persistente, solo si el proveedor soporta MOVE;
- proponer la eliminación permanente solo de archivos o carpetas vacías, después de confirmación visible y vinculada a la solicitud exacta.

La ruta se expresa como relativa a la raíz concedida. Se rechazan rutas absolutas, segmentos `..`, separadores alternativos, controles y rutas fuera del árbol. El modelo original `Modelos/gemma-4-E2B-it.litertlm` está protegido frente a eliminación por esta política.

## Implementación actual en esta rama

`BardoMobileFilePolicy` valida operaciones y rutas relativas, rechaza traversal y limita las operaciones al árbol SAF persistente. `BardoSharedStorage` ya implementa:
- listado de hijos directos con metadatos;
- lectura por tramos de texto UTF-8, permitiendo recorrer documentos grandes sin cargarlos completos en RAM;
- creación y verificación de archivos de texto (hasta 24.000 caracteres y 48 KiB UTF-8);
- creación recursiva de rutas de carpetas dentro del árbol BARDO, reutilizando solo directorios existentes;
- cola persistente de solicitudes de retirada;
- traslado a `Papelera` cuando el proveedor SAF soporta MOVE;
- eliminación permanente solo de archivos o carpetas vacías y después de confirmación visible;
- registro local de las operaciones, sin guardar el contenido de los archivos.

El catálogo expone listado, lectura por tramos con desplazamiento, creación de carpetas/archivos, listado de solicitudes pendientes y creación de solicitudes de retirada. El parser de solicitudes exige exactamente ruta, elección booleana explícita y motivo no vacío; entradas ambiguas o adicionales se rechazan. BARDO nunca recibe una herramienta que apruebe o ejecute eliminaciones. La pantalla Developer muestra ruta, motivo y naturaleza de la operación; el usuario puede aprobar o rechazar. El Gemma original sigue protegido.

`BardoMobileFilePolicyTest` cubre traversal, rutas absolutas, raíz, permisos rutinarios, aprobación vinculada a ruta/operación, caducidad y protección del Gemma original. La ejecución real SAF y compatibilidad de MOVE requieren prueba en el dispositivo.

## Mantenimiento de skills

`BardoSkillMaintenancePlanner` analiza compatibilidad con la API, acciones ausentes, evidencia de pruebas y versiones posteriores habilitadas. Solo emite recomendaciones:
- conservar;
- revisar compatibilidad;
- volver a probar;
- proponer deshabilitar;
- proponer retirar una versión sustituida o rechazada.

No deshabilita ni elimina por sí mismo. La aplicación de las recomendaciones debe pasar por el gestor de skills y por la autorización correspondiente. La retirada permanente requiere aprobación humana explícita.

## Evidencia pendiente antes de declararlo probado en el móvil

- Verificar en el Xiaomi 11T Pro la selección de `Descargas/BARDO`, persistencia de la concesión SAF, lectura y creación real, y compatibilidad del proveedor con MOVE/Papelera.
- Ampliar lectura de formatos grandes/binarios mediante módulos especializados, sin cargar modelos ni medios completos en RAM.
- Añadir pruebas instrumentadas con un DocumentProvider de prueba y pruebas de recuperación ante interrupciones.
- Ejecutar las pruebas y compilación de la revisión actual en Actions, instalar el APK candidato y verificar comportamiento real en Xiaomi 11T Pro.

La implementación SAF está escrita y sus políticas tienen pruebas unitarias; no se afirma todavía que se haya probado en el dispositivo físico. La compatibilidad de MOVE depende del DocumentProvider.
