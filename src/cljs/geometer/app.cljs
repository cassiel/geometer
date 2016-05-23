(ns geometer.app
  (:require [thi.ng.domus.core              :as dom]
            [thi.ng.geom.core               :as g]
            [thi.ng.geom.vector             :as v :refer [vec2 vec3]]
            [thi.ng.geom.matrix             :as mat :refer [M44]]
            [thi.ng.geom.utils              :as gu]
            [thi.ng.geom.rect               :refer [rect]]
            [thi.ng.geom.gl.core            :as gl]
            [thi.ng.geom.gl.webgl.animator  :refer [animate]]
            [thi.ng.geom.gl.webgl.constants :as glc]
            [thi.ng.geom.gl.shaders         :as sh]
            [thi.ng.geom.gl.shaders.phong   :as phong]
            [thi.ng.geom.gl.buffers         :as buf]
            [thi.ng.math.core               :as m]
            [thi.ng.typedarrays.core        :refer [float32]]
            [geometer.csg                   :as csg]
            [geometer.lsystem               :as lsystem]
            [geometer.shapes                :as shapes]
            [geometer.turtle                :as turtle]))

(enable-console-print!)

;; we use defonce for the webgl context, mouse tracking atoms, etc, so
;; they won't be re-initialed when the namespace is reloaded
(defonce gl (gl/gl-context "main"))
(defonce view-rect (atom nil))

(defonce mouse-x (atom 0))
(defonce mouse-y (atom 0))

(defonce projection (atom nil))
(defonce model (atom nil))

(defonce viewpoint (atom (g/translate M44 0 0 -70)))
(defonce eye-separation (atom 0))
(defonce render-mode (atom :normal))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; OPTIONS MENU + HELPERS

(defn- set-model!
  "Our model is a `mesh` that will be rendered by the animation loop started in the start function."
  [mesh]
  (reset! model
          (-> (g/center mesh)
              (gl/as-gl-buffer-spec {})
              (assoc :shader (sh/make-shader-from-spec gl phong/shader-spec))
              (update-in [:uniforms] merge
                         {:proj          @projection
                          :lightPos      (vec3 2 0 5)
                          :ambientCol    0x181818
                          :diffuseCol    0x10a04c
                          :specularCol   0xaaaaaa
                          :shininess     100
                          :wrap          1
                          :useBlinnPhong true})
              (gl/make-buffers-in-spec gl glc/static-draw))))

(defn build-model
  "Build and set a new model using `model-fn`."
  [model-fn]
  (let [status (dom/by-id "status")]
    (dom/set-html! status (str "Generating new model..."))
    (dom/set-class! status "visible")
    (js/setTimeout
     #(do
        (set-model! (model-fn))
        (dom/set-class! status "invisible"))
     20)))

(defn hud-message
  "Display message in HUD overlay."
  [body]
  (dom/set-html! (dom/by-id "hud") body))

(defn set-render-mode
  "Switch between `:normal` and `:stereo` rendering."
  [id]
  (reset! render-mode id)
  (hud-message
   (when (= :stereo @render-mode)
     (str "eye separation: " @eye-separation))))

(def options
  (array-map
   "Shapes"    [["cube"        #(build-model shapes/cube)]
                ["disc"        #(build-model shapes/disc)]
                ["sphere"      #(build-model shapes/sphere)]]
   "CSG"       [["starfighter" #(build-model csg/starfighter)]]
   "3D Turtle" [["hoops"       #(build-model turtle/hoops)]
                ["hexen"       #(build-model turtle/hexen)]
                ["plant"       #(build-model turtle/plant)]]
   "L-Systems" [["koch"        #(build-model lsystem/koch)]]
   "Mode"      [["normal"      #(set-render-mode :normal)]
                ["stereo"      #(set-render-mode :stereo)]]))

(def option-name-to-fn
  (apply hash-map (flatten (vals options))))

(def options-markup
  [:dl
   (for [[category ds] options]
     [:dt category
      (for [[d _] ds]
        [:dd {:onclick (str "geometer.app.handle_option('" d "');")} d])])])

(defn ^:export handle-option
  "Dispatch a UI callback to the right option function."
  [demo-name]
  ((option-name-to-fn demo-name)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- keypress-handler [e]
  (let [k (.-keyCode e)]
    (case k
      119 (swap! viewpoint g/translate 0 0 2)  ;; w = forward
      115 (swap! viewpoint g/translate 0 0 -2) ;; s = backward
      91  (swap! eye-separation - 0.01)        ;; [ = -eye separation
      93  (swap! eye-separation + 0.01)        ;; ] = +eye separation
      (print k))
    (when (= :stereo @render-mode)
      (hud-message (str "eye separation: " @eye-separation)))))

(defn resize-handler
  "Window resize handler. Resizes canvas, updates view rects and
  projection matrices."
  []
  (let [w      (.-innerWidth js/window)
        h      (.-innerHeight js/window)
        canv   (.getElementById js/document "main")
        w2     (/ w 2)
        view   (rect 0 0 w h)
        view-l (rect 0 0 w2 h)
        view-r (rect w2 0 w2 h)]
    (set! (.-width canv) w)
    (set! (.-height canv) h)
    (reset! view-rect
            {:normal view
             :left   view-l
             :right  view-r})
    (reset! projection
            {:normal (gl/perspective 45 view 0.1 200.0)
             :left   (gl/perspective 45 view-l 0.1 200.0)
             :right  (gl/perspective 45 view-l 0.1 200.0)})))

(defn- update-pos [e]
  (reset! mouse-x (* 0.01 (- (.-clientX e) (/ (.-innerWidth js/window) 2))))
  (reset! mouse-y (* 0.01 (- (.-clientY e) (/ (.-innerHeight js/window) 2)))))

(defn- draw-model-in-view
  "Takes a semi-complete model spec map, view id and eye separation
  offset. Sets up and clears viewport, enables scissor test and draws
  mesh with injected projection and view matrices for requested view."
  [model id eye-sep]
  (let [{[x y] :p [w h] :size :as view} (@view-rect id)]
    (gl/set-viewport gl view)
    (gl/enable gl glc/scissor-test)
    (gl/scissor-test gl x y w h)
    (gl/clear-color-buffer gl 0 0 0 0) ;; 0 opacity, so we see the bg gradient
    (gl/clear-depth-buffer gl 1)
    (gl/enable gl glc/depth-test)
    (buf/draw-arrays-with-shader gl
                                 (update model :uniforms merge
                                         {:proj (@projection id)
                                          :view (mat/look-at (vec3 eye-sep 0 2) (vec3) (vec3 0 1 0))}))
    (gl/disable gl glc/scissor-test)))

(defn ^:export start
  "This function is called when 'index.html' loads. We use it to kick off mouse tracking, a keyboard handler and the animation loop."
  []
  ;; set up the options menu
  (dom/create-dom! options-markup (dom/by-id "options"))

  ;; event handlers
  (.addEventListener js/document "keypress" keypress-handler)
  (.addEventListener js/document "mousemove" update-pos)
  (.addEventListener js/window "touchmove"
                     #(do (.preventDefault %)
                          (update-pos (aget (.-touches %) 0))))
  (.addEventListener js/window "resize" resize-handler)
  (resize-handler)

  ;; initialize with a cube
  (set-model! (shapes/cube))

  ;; the animation loop :-)
  (animate
   (fn [t frame]
     (let [m (update @model :uniforms merge
                     {:model (-> @viewpoint
                                 (g/translate 0 0 0)
                                 (g/rotate-x @mouse-y)
                                 (g/rotate-y @mouse-x))})]
       (if (= :normal @render-mode)
         (draw-model-in-view m :normal 0)
         (do
           (draw-model-in-view m :left (- @eye-separation))
           (draw-model-in-view m :right (+ @eye-separation))))
       true))))
