// Список фотографий. Чтобы вставить свои — положи файлы в папку images/
// и поменяй пути ниже (или добавь новые объекты).
const photos = [
  { src: "images/photo-1.svg", title: "Свет в городе" },
  { src: "images/photo-2.svg", title: "Портрет" },
  { src: "images/photo-3.svg", title: "Утро" },
  { src: "images/photo-4.svg", title: "Архитектура" },
  { src: "images/photo-5.svg", title: "Дорога" },
  { src: "images/photo-6.svg", title: "Тишина" },
];

const grid = document.getElementById("grid");
const lightbox = document.getElementById("lightbox");
const lightboxImg = document.getElementById("lightboxImg");
const lightboxClose = document.getElementById("lightboxClose");

// Рендер карточек
photos.forEach((p) => {
  const card = document.createElement("figure");
  card.className = "card";
  card.innerHTML = `
    <img src="${p.src}" alt="${p.title}" loading="lazy" />
    <figcaption class="card-cap">${p.title}</figcaption>
  `;
  card.addEventListener("click", () => openLightbox(p.src, p.title));
  grid.appendChild(card);
});

// Лайтбокс
function openLightbox(src, title) {
  lightboxImg.src = src;
  lightboxImg.alt = title;
  lightbox.hidden = false;
  document.body.style.overflow = "hidden";
}
function closeLightbox() {
  lightbox.hidden = true;
  lightboxImg.src = "";
  document.body.style.overflow = "";
}

lightboxClose.addEventListener("click", closeLightbox);
lightbox.addEventListener("click", (e) => {
  if (e.target === lightbox) closeLightbox();
});
document.addEventListener("keydown", (e) => {
  if (e.key === "Escape" && !lightbox.hidden) closeLightbox();
});
