package com.project.souklab.config;

import com.project.souklab.dao.EpoqueRepository;
import com.project.souklab.dao.JobCategoryRepository;
import com.project.souklab.dao.JobSubCategoryRepository;
import com.project.souklab.dao.MaterialFamilyRepository;
import com.project.souklab.dao.MaterialRepository;
import com.project.souklab.dao.RegionRepository;
import com.project.souklab.dao.AuthorizationPermissionRepository;
import com.project.souklab.dao.TechniqueRepository;
import com.project.souklab.dao.UserRepository;
import com.project.souklab.model.AccountStatus;
import com.project.souklab.model.Epoque;
import com.project.souklab.model.JobCategory;
import com.project.souklab.model.JobSubCategory;
import com.project.souklab.model.Material;
import com.project.souklab.model.MaterialFamily;
import com.project.souklab.model.Region;
import com.project.souklab.model.AuthorizationPermission;
import com.project.souklab.model.Technique;
import com.project.souklab.model.User;
import com.project.souklab.security.Permission;
import com.project.souklab.util.EmailUtil;
import com.project.souklab.util.SlugUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reference data seeder initializing permissions, admin user, geographic hierarchy,
 * craft categories, material families, historical epochs, and craftsmanship techniques.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final AuthorizationPermissionRepository permissionRepository;
    private final PasswordEncoder passwordEncoder;
    private final AdminProperties adminProperties;
    private final EmailUtil emailUtil;
    private final Clock clock;
    private final RegionRepository regionRepository;
    private final JobCategoryRepository jobCategoryRepository;
    private final JobSubCategoryRepository jobSubCategoryRepository;
    private final MaterialFamilyRepository materialFamilyRepository;
    private final MaterialRepository materialRepository;
    private final EpoqueRepository epoqueRepository;
    private final TechniqueRepository techniqueRepository;

    @Override
    @Transactional
    public void run(String... args) {
        seedPermissions();
        if (adminProperties.isBootstrapEnabled()) {
            seedAdminUser();
        } else {
            log.info("Admin bootstrap is disabled; reference data seeding remains enabled");
        }
        seedRegions();
        seedJobCategories();
        seedMaterials();
        seedEpoques();
        seedTechniques();
    }

    private void seedPermissions() {
        for (Permission permission : Permission.all()) {
            if (permissionRepository.findByPermissionKeyAndEnabledTrue(permission.value()).isEmpty()) {
                AuthorizationPermission definition = new AuthorizationPermission();
                definition.setPermissionKey(permission.value());
                definition.setDescription(permission.description());
                definition.setEnabled(true);
                permissionRepository.save(definition);
            }
        }
    }

    private void seedAdminUser() {
        String defaultEmail = adminProperties.getDefaultEmail();
        if (defaultEmail == null || defaultEmail.isBlank()) {
            throw new IllegalStateException("APP_ADMIN_DEFAULT_EMAIL must be configured in the environment");
        }
        String normalizedEmail = defaultEmail.trim().toLowerCase();

        if (userRepository.existsByEmail(normalizedEmail)) {
            return;
        }

        Set<AuthorizationPermission> adminPermissions = new HashSet<>(permissionRepository.findAll());

        String defaultPassword = adminProperties.getDefaultPassword();
        if (defaultPassword == null || defaultPassword.isBlank()) {
            throw new IllegalStateException("APP_ADMIN_DEFAULT_PASSWORD must be configured in the environment");
        }

        User admin = User.builder()
                .email(normalizedEmail)
                .password(passwordEncoder.encode(defaultPassword))
                .firstName("System")
                .lastName("Administrator")
                .status(AccountStatus.ACTIVE)
                .emailVerified(true)
                .emailVerifiedAt(LocalDateTime.now(clock))
                .permissions(adminPermissions)
                .build();

        userRepository.save(admin);

        try {
            emailUtil.sendAdminWelcomeEmail(normalizedEmail, defaultPassword);
        } catch (Exception e) {
            log.error("Failed to send first-boot administrator welcome email to {}", normalizedEmail, e);
        }
    }

    private void seedRegions() {
        if (regionRepository.count() > 0) {
            return;
        }

        Region algerie = Region.builder()
            .name("Algérie")
            .slug("algerie")
            .code("DZ")
            .displayOrder(1)
            .isActive(true)
            .parent(null)
            .build();
        algerie = regionRepository.save(algerie);

        Region france = Region.builder()
            .name("France")
            .slug("france")
            .code("FR")
            .displayOrder(2)
            .isActive(true)
            .parent(null)
            .build();
        france = regionRepository.save(france);

        String[][] wilayasData = {
            {"01", "Adrar", "adrar"},
            {"02", "Chlef", "chlef"},
            {"03", "Laghouat", "laghouat"},
            {"04", "Oum El Bouaghi", "oum-el-bouaghi"},
            {"05", "Batna", "batna"},
            {"06", "Béjaïa", "bejaia"},
            {"07", "Biskra", "biskra"},
            {"08", "Béchar", "bechar"},
            {"09", "Blida", "blida"},
            {"10", "Bouira", "bouira"},
            {"11", "Tamanrasset", "tamanrasset"},
            {"12", "Tébessa", "tebessa"},
            {"13", "Tlemcen", "tlemcen"},
            {"14", "Tiaret", "tiaret"},
            {"15", "Tizi Ouzou", "tizi-ouzou"},
            {"16", "Alger", "alger"},
            {"17", "Djelfa", "djelfa"},
            {"18", "Jijel", "jijel"},
            {"19", "Sétif", "setif"},
            {"20", "Saïda", "saida"},
            {"21", "Skikda", "skikda"},
            {"22", "Sidi Bel Abbès", "sidi-bel-abbes"},
            {"23", "Annaba", "annaba"},
            {"24", "Guelma", "guelma"},
            {"25", "Constantine", "constantine"},
            {"26", "Médéa", "medea"},
            {"27", "Mostaganem", "mostaganem"},
            {"28", "M'Sila", "msila"},
            {"29", "Mascara", "mascara"},
            {"30", "Ouargla", "ouargla"},
            {"31", "Oran", "oran"},
            {"32", "El Bayadh", "el-bayadh"},
            {"33", "Illizi", "illizi"},
            {"34", "Bordj Bou Arreridj", "bordj-bou-arreridj"},
            {"35", "Boumerdès", "boumerdes"},
            {"36", "El Tarf", "el-tarf"},
            {"37", "Tindouf", "tindouf"},
            {"38", "Tissemsilt", "tissemsilt"},
            {"39", "El Oued", "el-oued"},
            {"40", "Khenchela", "khenchela"},
            {"41", "Souk Ahras", "souk-ahras"},
            {"42", "Tipaza", "tipaza"},
            {"43", "Mila", "mila"},
            {"44", "Aïn Defla", "ain-defla"},
            {"45", "Naâma", "naama"},
            {"46", "Aïn Témouchent", "ain-temouchent"},
            {"47", "Ghardaïa", "ghardaia"},
            {"48", "Relizane", "relizane"},
            {"49", "Timimoun", "timimoun"},
            {"50", "Bordj Badji Mokhtar", "bordj-badji-mokhtar"},
            {"51", "Ouled Djellal", "ouled-djellal"},
            {"52", "Béni Abbès", "beni-abbes"},
            {"53", "In Salah", "in-salah"},
            {"54", "In Guezzam", "in-guezzam"},
            {"55", "Touggourt", "touggourt"},
            {"56", "Djanet", "djanet"},
            {"57", "El M'Ghair", "el-mghair"},
            {"58", "El Meniaa", "el-meniaa"},
            {"59", "Aflou", "aflou"},
            {"60", "Barika", "barika"},
            {"61", "El Kantara", "el-kantara"},
            {"62", "Bir el-Ater", "bir-el-ater"},
            {"63", "El Aricha", "el-aricha"},
            {"64", "Ksar Chellala", "ksar-chellala"},
            {"65", "Aïn Oussera", "ain-oussera"},
            {"66", "Messaad", "messaad"},
            {"67", "Ksar El Boukhari", "ksar-el-boukhari"},
            {"68", "Bou Saâda", "bou-saada"},
            {"69", "El Abiodh Sidi Cheikh", "el-abiodh-sidi-cheikh"}
        };

        Map<String, Region> wilayaMap = new HashMap<>();
        for (int i = 0; i < wilayasData.length; i++) {
            String code = wilayasData[i][0];
            String name = wilayasData[i][1];
            String slug = wilayasData[i][2];
            int displayOrder = i + 1;
            Region wilaya = Region.builder()
                .name(name)
                .slug(slug)
                .code(code)
                .displayOrder(displayOrder)
                .isActive(true)
                .parent(algerie)
                .build();
            wilaya = regionRepository.save(wilaya);
            wilayaMap.put(code, wilaya);
        }

        Region idf = Region.builder()
            .name("Île-de-France")
            .slug("ile-de-france")
            .code("IDF")
            .displayOrder(1)
            .isActive(true)
            .parent(france)
            .build();
        idf = regionRepository.save(idf);

        Region ara = Region.builder()
            .name("Auvergne-Rhône-Alpes")
            .slug("auvergne-rhone-alpes")
            .code("ARA")
            .displayOrder(2)
            .isActive(true)
            .parent(france)
            .build();
        ara = regionRepository.save(ara);

        Region paca = Region.builder()
            .name("Provence-Alpes-Côte d'Azur")
            .slug("provence-alpes-cote-d-azur")
            .code("PACA")
            .displayOrder(3)
            .isActive(true)
            .parent(france)
            .build();
        paca = regionRepository.save(paca);

        Region tiziOuzou = wilayaMap.get("15");
        Region alger = wilayaMap.get("16");
        Region ghardaia = wilayaMap.get("47");

        List<Region> communes = List.of(
            Region.builder()
                .name("Beni Yenni")
                .slug("beni-yenni")
                .code(null)
                .displayOrder(1)
                .isActive(true)
                .parent(tiziOuzou)
                .build(),
            Region.builder()
                .name("Azazga")
                .slug("azazga")
                .code(null)
                .displayOrder(2)
                .isActive(true)
                .parent(tiziOuzou)
                .build(),
            Region.builder()
                .name("Casbah")
                .slug("casbah")
                .code(null)
                .displayOrder(1)
                .isActive(true)
                .parent(alger)
                .build(),
            Region.builder()
                .name("Beni Isguen")
                .slug("beni-isguen")
                .code(null)
                .displayOrder(1)
                .isActive(true)
                .parent(ghardaia)
                .build(),
            Region.builder()
                .name("Paris")
                .slug("paris")
                .code(null)
                .displayOrder(1)
                .isActive(true)
                .parent(idf)
                .build(),
            Region.builder()
                .name("Lyon")
                .slug("lyon")
                .code(null)
                .displayOrder(1)
                .isActive(true)
                .parent(ara)
                .build(),
            Region.builder()
                .name("Marseille")
                .slug("marseille")
                .code(null)
                .displayOrder(1)
                .isActive(true)
                .parent(paca)
                .build()
        );
        regionRepository.saveAll(communes);
    }

    private void seedJobCategories() {
        if (jobCategoryRepository.count() > 0) {
            return;
        }

        saveCategoryWithSubs(
            "Gros œuvre & structure",
            "Travaux structuraux, maçonnerie, terrassement et ossatures du bâtiment.",
            "category-gros-oeuvre-structure.png",
            1,
            List.of(
                new SubCategorySeed("Maçon", "Construction et réhabilitation de structures porteuses, murs et fondations.", 1),
                new SubCategorySeed("Tailleur de pierre", "Façonnage et appareillage de pierres pour murs, arcs et édifices.", 2),
                new SubCategorySeed("Charpentier bois / charpentier de marine", "Conception, taille et assemblage de charpentes et structures en bois.", 3),
                new SubCategorySeed("Coffreur-bancheur", "Réalisation d'ouvrages en béton armé à l'aide de coffrages et banches.", 4),
                new SubCategorySeed("Ferrailleur", "Façonnage et pose des armatures métalliques pour béton armé.", 5),
                new SubCategorySeed("Terrassier / VRD", "Préparation des sols, terrassement et voiries et réseaux divers.", 6)
            )
        );

        saveCategoryWithSubs(
            "Toiture & enveloppe du bâtiment",
            "Couverture, étanchéité, zinguerie et protection extérieure du bâtiment.",
            "category-toiture-enveloppe-du-batiment.png",
            2,
            List.of(
                new SubCategorySeed("Couvreur (tuiles, ardoises)", "Pose et restauration de couvertures en tuiles et ardoises.", 1),
                new SubCategorySeed("Zingueur", "Façonnage et pose d'éléments métalliques pour l'évacuation des eaux pluviales et l'étanchéité.", 2),
                new SubCategorySeed("Étancheur", "Mise en œuvre de revêtements d'étanchéité pour toitures-terrasses et façades.", 3),
                new SubCategorySeed("Façadier / enduiseur", "Application d'enduits et ravalement protecteur des façades.", 4),
                new SubCategorySeed("Bardeur", "Pose de bardages isolants et décoratifs sur les façades extérieures.", 5)
            )
        );

        saveCategoryWithSubs(
            "Électricité & énergie",
            "Installations électriques, énergies renouvelables et gestion technique du bâtiment.",
            "category-electricite-energie.png",
            3,
            List.of(
                new SubCategorySeed("Électricien bâtiment", "Installation, raccordement et mise aux normes des réseaux électriques.", 1),
                new SubCategorySeed("Installateur photovoltaïque", "Pose et maintenance de panneaux et systèmes solaires photovoltaïques.", 2),
                new SubCategorySeed("Technicien domotique", "Automatisation, pilotage intelligent et connectivité des équipements de l'habitat.", 3),
                new SubCategorySeed("Automaticien / GTB", "Programmation et maintenance de systèmes de gestion technique du bâtiment.", 4)
            )
        );

        saveCategoryWithSubs(
            "Plomberie & systèmes techniques",
            "Distribution d'eau, sanitaires, réseaux thermiques et climatisation.",
            "category-plomberie-systemes-techniques.png",
            4,
            List.of(
                new SubCategorySeed("Plombier sanitaire", "Installation et maintenance des réseaux d'alimentation et d'évacuation sanitaire.", 1),
                new SubCategorySeed("Chauffagiste", "Installation et maintenance de chaudières, radiateurs et pompes à chaleur.", 2),
                new SubCategorySeed("Frigoriste / climaticien", "Mise en service et entretien d'installations frigorifiques et de climatisation.", 3),
                new SubCategorySeed("Technicien CVC (chauffage-ventilation-climatisation)", "Gestion globale des flux d'air, de température et de ventilation du bâtiment.", 4)
            )
        );

        saveCategoryWithSubs(
            "Finitions & second œuvre",
            "Aménagements intérieurs, cloisons, revêtements muraux et de sol.",
            "category-finitions-second-oeuvre.png",
            5,
            List.of(
                new SubCategorySeed("Peintre en bâtiment", "Préparation des supports et application de peintures et revêtements muraux.", 1),
                new SubCategorySeed("Carreleur-mosaïste", "Pose géométrique de carrelages, dalles et mosaïques décoratives.", 2),
                new SubCategorySeed("Plâtrier-plaquiste", "Pose de cloisons sèches, faux plafonds et finitions en plâtre.", 3),
                new SubCategorySeed("Parqueteur", "Pose, ponçage et vitrification de parquets massifs et contrecollés.", 4),
                new SubCategorySeed("Solier-moquettiste", "Découpe et pose de revêtements de sol souples, moquettes et linoléum.", 5)
            )
        );

        saveCategoryWithSubs(
            "Menuiserie & agencement",
            "Fabrication et pose de fermetures, mobiliers et agencements intérieurs.",
            "category-menuiserie-agencement.png",
            6,
            List.of(
                new SubCategorySeed("Menuisier bois / aluminium / PVC", "Fabrication et pose de fenêtres, portes et baies vitrées tous matériaux.", 1),
                new SubCategorySeed("Ébéniste", "Création, restauration et marqueterie de meubles et boiseries d'art.", 2),
                new SubCategorySeed("Menuisier agenceur", "Conception et installation sur mesure d'aménagements intérieurs et placards.", 3),
                new SubCategorySeed("Cuisiniste", "Conception, assemblage et pose de cuisines équipées et fonctionnelles.", 4)
            )
        );

        saveCategoryWithSubs(
            "Métal & serrurerie",
            "Ouvrages en métal, serrures, fermetures de sécurité et métallerie décorative.",
            "category-metal-serrurerie.png",
            7,
            List.of(
                new SubCategorySeed("Serrurier", "Pose, dépannage et maintenance de systèmes de verrouillage et serrures de sécurité.", 1),
                new SubCategorySeed("Métallier", "Fabrication d'ouvrages métalliques légers, garde-corps, escaliers et passerelles.", 2),
                new SubCategorySeed("Ferronnier d'art", "Façonnage à chaud du fer et de métaux pour éléments décoratifs et d'art.", 3),
                new SubCategorySeed("Chaudronnier", "Conformation et assemblage de tôles et tubes pour tuyauteries et cuves métalliques.", 4)
            )
        );

        saveCategoryWithSubs(
            "Métiers du patrimoine",
            "Restauration, conservation et techniques traditionnelles des monuments et du bâti ancien.",
            "category-metiers-du-patrimoine.png",
            8,
            List.of(
                new SubCategorySeed("Maçon du patrimoine", "Restauration d'ouvrages anciens à la chaux, moellons et techniques d'époque.", 1),
                new SubCategorySeed("Couvreur du patrimoine", "Réfection de toitures classées, dômes et couvertures historiques.", 2),
                new SubCategorySeed("Vitrailliste", "Création et restauration de vitraux d'art montés au plomb.", 3),
                new SubCategorySeed("Staffeur-ornemaniste", "Moulage et restauration de corniches, rosaces et stucs d'ornement.", 4),
                new SubCategorySeed("Marbrier", "Découpe, polissage et restauration d'éléments architecturaux en marbre.", 5)
            )
        );
    }

    private void saveCategoryWithSubs(String name, String description, String iconUrl, int displayOrder, List<SubCategorySeed> subs) {
        JobCategory category = JobCategory.builder()
            .name(name)
            .slug(SlugUtils.toSlug(name))
            .description(description)
            .iconUrl(iconUrl)
            .displayOrder(displayOrder)
            .isActive(true)
            .build();
        category = jobCategoryRepository.save(category);

        for (SubCategorySeed sub : subs) {
            JobSubCategory subCategory = JobSubCategory.builder()
                .name(sub.displayName())
                .slug(sub.slug())
                .description(sub.description())
                .displayOrder(sub.displayOrder())
                .isActive(true)
                .category(category)
                .build();
            jobSubCategoryRepository.save(subCategory);
        }
    }

    private void seedMaterials() {
        if (materialFamilyRepository.count() > 0) {
            return;
        }

        saveMaterialFamilyWithMaterials(
            "Matériaux naturels traditionnels",
            "Substances naturelles brutes d'origine minérale ou végétale utilisées dans l'architecture méditerranéenne.",
            1,
            List.of(
                new MaterialSeed("Pierre calcaire", "Roche sédimentaire calcaire tendre ou dure pour maçonnerie, taille et dallages.", 1),
                new MaterialSeed("Marbre", "Roche métamorphique noble polie pour revêtements, colonnes et éléments ornementaux.", 2),
                new MaterialSeed("Terre crue (pisé, adobe, torchis)", "Terre argileuse compactée ou façonnée séchée au soleil offrant régulation hygrothermique.", 3),
                new MaterialSeed("Argile", "Matière minérale plastique employée pour briques, céramiques et poteries traditionnelles.", 4),
                new MaterialSeed("Bois (pin, cyprès, chêne)", "Essences de bois régionales pour charpente, menuiserie et structures extérieures.", 5)
            )
        );

        saveMaterialFamilyWithMaterials(
            "Matériaux de maçonnerie",
            "Éléments et liants manufacturés pour l'élévation des murs et le gros œuvre.",
            2,
            List.of(
                new MaterialSeed("Brique (pleine, creuse, terre cuite)", "Éléments modulaires en terre cuite pour cloisons et murs porteurs.", 1),
                new MaterialSeed("Parpaing (béton)", "Bloc de béton manufacturé pour maçonnerie courante et soubassements.", 2),
                new MaterialSeed("Mortier / chaux naturelle", "Liant respirant à base de chaux et sable pour montage de maçonneries et enduits.", 3),
                new MaterialSeed("Béton armé", "Matériau composite de béton et d'acier conférant une haute résistance structurelle.", 4)
            )
        );

        saveMaterialFamilyWithMaterials(
            "Matériaux de toiture",
            "Matériaux assurant la couverture, l'écoulement des eaux et la protection des toitures.",
            3,
            List.of(
                new MaterialSeed("Tuiles en terre cuite (romanes méditerranéennes)", "Tuiles galbées traditionnelles adaptées aux climats ensoleillés du pourtour méditerranéen.", 1),
                new MaterialSeed("Ardoise (zones plus nordiques)", "Feuillets de schiste imperméable pour toitures à pente prononcée.", 2),
                new MaterialSeed("Zinc", "Métal laminé malléable pour toitures, chéneaux, noues et évacuations pluviales.", 3),
                new MaterialSeed("Béton de toiture", "Dalles et tuiles en béton offrant compacité et résistance mécanique sous toiture.", 4)
            )
        );

        saveMaterialFamilyWithMaterials(
            "Métal & structure",
            "Profilés et alliages métalliques pour structures porteuses et ferronnerie d'art.",
            4,
            List.of(
                new MaterialSeed("Acier", "Alliage de fer et de carbone à haute limite élastique pour ossatures et renforts.", 1),
                new MaterialSeed("Aluminium", "Métal léger inoxydable pour profilés de menuiserie et façades modernes.", 2),
                new MaterialSeed("Fer forgé", "Fer chauffé et martelé artisanalement pour grilles, rampes et ferronneries décoratives.", 3)
            )
        );

        saveMaterialFamilyWithMaterials(
            "Isolation & techniques modernes",
            "Isolants thermiques, acoustiques et membranes techniques pour la performance énergétique.",
            5,
            List.of(
                new MaterialSeed("Laine de roche", "Isolant minéral fibreux incombustible pour isolation thermique et acoustique.", 1),
                new MaterialSeed("Laine de verre", "Matelas isolant à base de silice pour combles, cloisons et toitures.", 2),
                new MaterialSeed("Polystyrène expansé", "Panneaux isolants rigides ultra-légers pour isolation par l'extérieur et sous chape.", 3),
                new MaterialSeed("Polyuréthane", "Mousse rigide à haut pouvoir isolant et faible épaisseur.", 4),
                new MaterialSeed("Membranes d'étanchéité", "Feuilles élastomères ou bitumineuses assurant l'étanchéité à l'eau et à l'air.", 5)
            )
        );

        saveMaterialFamilyWithMaterials(
            "Revêtements & finitions",
            "Couches décoratives et de protection pour l'embellissement des surfaces intérieures et extérieures.",
            6,
            List.of(
                new MaterialSeed("Enduits à la chaux", "Enduits traditionnels microporeux minéraux régulant l'humidité des parois.", 1),
                new MaterialSeed("Peintures acryliques", "Peintures en phase aqueuse résistantes aux UV et intempéries.", 2),
                new MaterialSeed("Carrelage / céramique", "Dalles émaillées ou grès cérame pour sols et murs résistant à l'usure et à l'eau.", 3),
                new MaterialSeed("Mosaïque", "Composition décorative de petits fragments minéraux ou émaux assemblés avec finesse.", 4)
            )
        );
    }

    private void saveMaterialFamilyWithMaterials(String name, String description, int displayOrder, List<MaterialSeed> materials) {
        MaterialFamily family = MaterialFamily.builder()
            .name(name)
            .slug(SlugUtils.toSlug(name))
            .description(description)
            .displayOrder(displayOrder)
            .isActive(true)
            .build();
        family = materialFamilyRepository.save(family);

        for (MaterialSeed mat : materials) {
            Material material = Material.builder()
                .name(mat.displayName())
                .slug(mat.slug())
                .description(mat.description())
                .displayOrder(mat.displayOrder())
                .isActive(true)
                .family(family)
                .build();
            materialRepository.save(material);
        }
    }

    private void seedEpoques() {
        if (epoqueRepository.count() > 0) {
            return;
        }

        List<Epoque> epoques = List.of(
            Epoque.builder()
                .name("Période Numide")
                .slug("periode-numide")
                .periodEra("IIIe siècle av. J.-C. - Ier siècle ap. J.-C.")
                .description("Royaume des rois numides (Massinissa, Syphax, Juba), marqué par une orfèvrerie et une poterie aux formes architecturales souveraines.")
                .displayOrder(1)
                .isActive(true)
                .build(),
            Epoque.builder()
                .name("Période Rustumide & Médiévale")
                .slug("periode-rustumide-medievale")
                .periodEra("VIIIe - XIVe siècle")
                .description("Âge d'or des cités sahariennes et médiévales (Tahert, Sedrata, M'Zab) avec un essor remarquable du tissage géométrique et de l'architecture en terre.")
                .displayOrder(2)
                .isActive(true)
                .build(),
            Epoque.builder()
                .name("Période Zianide")
                .slug("periode-zianide")
                .periodEra("XIIIe - XVIe siècle")
                .description("Dynastie de Tlemcen caractérisée par le raffinement hispano-mauresque, le zellige ornemental, la dinanderie fine et le travail du bois sculpté.")
                .displayOrder(3)
                .isActive(true)
                .build(),
            Epoque.builder()
                .name("Époque Ottomane")
                .slug("epoque-ottomane")
                .periodEra("XVIe - XIXe siècle")
                .description("Régence d'Alger marquée par le costume d'apparat, les velours brodés au fil d'or (karakou), la dinanderie d'art et la céramique citadine.")
                .displayOrder(4)
                .isActive(true)
                .build(),
            Epoque.builder()
                .name("Période Traditionnelle Pré-moderne")
                .slug("periode-traditionnelle-pre-moderne")
                .periodEra("XIXe - milieu XXe siècle")
                .description("Préservation rurale des savoir-faire ancestraux au cœur des villages de montagne, des oasis et des campements nomades.")
                .displayOrder(5)
                .isActive(true)
                .build(),
            Epoque.builder()
                .name("Renaissance Artisanale Contemporaine")
                .slug("renaissance-artisanale-contemporaine")
                .periodEra("Époque contemporaine")
                .description("Courant moderne réinterprétant les motifs patrimoniaux algériens dans des créations design et écoresponsables.")
                .displayOrder(6)
                .isActive(true)
                .build()
        );
        epoqueRepository.saveAll(epoques);
    }

    private void seedTechniques() {
        if (techniqueRepository.existsBySlug("pise")) {
            return;
        }

        if (techniqueRepository.count() > 0) {
            techniqueRepository.deleteAllArtisanLinks();
            techniqueRepository.deleteAll();
        }

        List<Technique> techniques = List.of(
            Technique.builder()
                .name("Pisé")
                .slug("pise")
                .description("Technique de construction traditionnelle en terre crue compactée dans des coffrages, offrant une forte inertie thermique.")
                .displayOrder(1)
                .isActive(true)
                .build(),
            Technique.builder()
                .name("Enduit à la chaux")
                .slug("enduit-a-la-chaux")
                .description("Application d'enduits respirants à base de chaux aérienne ou hydraulique pour la protection et la finition des maçonneries anciennes.")
                .displayOrder(2)
                .isActive(true)
                .build(),
            Technique.builder()
                .name("Taille de pierre")
                .slug("taille-de-pierre")
                .description("Façonnage et appareillage de blocs de pierre naturelle pour la construction d'ouvrages porteurs ou la restauration de modénatures.")
                .displayOrder(3)
                .isActive(true)
                .build(),
            Technique.builder()
                .name("Maçonnerie en moellons")
                .slug("maconnerie-en-moellons")
                .description("Montage de murs en pierres brutes ou équarries liées au mortier traditionnel, assurant solidité et intégration paysagère.")
                .displayOrder(4)
                .isActive(true)
                .build(),
            Technique.builder()
                .name("Zellij")
                .slug("zellij")
                .description("Art décoratif de mosaïque géométrique en terre cuite émaillée, découpée et assemblée manuellement selon des motifs traditionnels.")
                .displayOrder(5)
                .isActive(true)
                .build(),
            Technique.builder()
                .name("Géjij (plâtre sculpté)")
                .slug("gejij-platre-sculpte")
                .description("Sculpture ornementale et ciselure manuelle sur plâtre traditionnel frais pour les frises, arcs, coupoles et plafonds décorés.")
                .displayOrder(6)
                .isActive(true)
                .build(),
            Technique.builder()
                .name("Charpente traditionnelle")
                .slug("charpente-traditionnelle")
                .description("Conception, assemblage à tenons et mortaises et pose de structures porteuses en bois massif pour toitures et planchers.")
                .displayOrder(7)
                .isActive(true)
                .build()
        );
        techniqueRepository.saveAll(techniques);
    }
}
